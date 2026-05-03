import asyncio
import base64
import io
import logging
import os
import shutil
from concurrent.futures import ThreadPoolExecutor
from typing import List

import cv2
import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s  %(levelname)s  %(message)s")
logger = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────
MODEL_PATH  = os.environ.get("MODEL_PATH",  "./triton-models/eosin_yolo/1/best.onnx")
CONF_THRESH = float(os.environ.get("CONF_THRESH", "0.25"))
IOU_THRESH  = float(os.environ.get("IOU_THRESH",  "0.45"))
INPUT_SIZE  = int(os.environ.get("INPUT_SIZE",    "448"))
# YOLO classes for OUR model (после fine-tune на 2 классах):
NUM_CLASSES = int(os.environ.get("NUM_CLASSES",   "2"))
WHITE_THRESH = float(os.environ.get("WHITE_THRESH", "240"))

# ── ORT session (single-threaded GPU executor) ────────────────────────────────
_available = ort.get_available_providers()
logger.info(f"Available ORT providers: {_available}")
if "CUDAExecutionProvider" not in _available:
    raise RuntimeError("CUDAExecutionProvider not available")

providers = [(
    "CUDAExecutionProvider",
    {
        "device_id": 0,
        "arena_extend_strategy": "kSameAsRequested",
        "cudnn_conv_use_max_workspace": "1",
        "do_copy_in_default_stream": True,
    },
), "CPUExecutionProvider"]

sess_opts = ort.SessionOptions()
sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
sess_opts.intra_op_num_threads = 1
sess_opts.inter_op_num_threads = 1
sess_opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL


def _build_session() -> ort.InferenceSession:
    return ort.InferenceSession(MODEL_PATH,
                                sess_options=sess_opts,
                                providers=providers)


sess = _build_session()
input_name  = sess.get_inputs()[0].name
output_name = sess.get_outputs()[0].name  # detect head
out_shape   = sess.get_outputs()[0].shape
logger.info(f"ONNX input  {input_name} shape={sess.get_inputs()[0].shape}")
logger.info(f"ONNX output {output_name} shape={out_shape} (всего выходов: "
            f"{len(sess.get_outputs())})")

# Sanity check: проверим, что модель — на правильное число классов.
# YOLO detect head: (4 + nc, anchors). YOLO seg head: (4 + nc + 32, anchors).
expected_detect = 4 + NUM_CLASSES
expected_seg    = 4 + NUM_CLASSES + 32
ch = out_shape[1] if isinstance(out_shape[1], int) else None
if ch is not None and ch not in (expected_detect, expected_seg):
    logger.warning(
        f"!!! ONNX output channels = {ch}, ожидали {expected_detect} (detect) "
        f"или {expected_seg} (seg) для NUM_CLASSES={NUM_CLASSES}. "
        f"Скорее всего в best.onnx лежит pretrained модель на COCO (80 классов). "
        f"Перевыгрузите дообученную модель в ONNX через `model.export(format='onnx')` "
        f"из чекпоинта вашего fine-tune."
    )

CLASS_NAMES = {0: "eos", 1: "eosg"}

# Однопоточный executor для GPU инференса — гарантирует, что в один и тот же
# момент только один тред работает с CUDA stream сессии.
GPU_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="ort-gpu")
# Лок на пересоздание сессии после сбоя CUDA
_session_lock = asyncio.Lock()


def _maybe_recreate_session(err: Exception):
    """После CUDA-700 контекст драйвера отравлен — пересоздаём сессию."""
    global sess, input_name, output_name
    msg = str(err)
    if "CUDA" in msg or "cudnn" in msg or "illegal memory access" in msg:
        logger.error("CUDA error detected, recreating ORT session...")
        try:
            sess = _build_session()
            input_name  = sess.get_inputs()[0].name
            output_name = sess.get_outputs()[0].name
            logger.info("ORT session recreated")
        except Exception as e:
            logger.exception(f"Failed to recreate session: {e}")


# ── FastAPI ───────────────────────────────────────────────────────────────────
app = FastAPI()


@app.on_event("startup")
def cleanup_runs():
    shutil.rmtree("runs", ignore_errors=True)
    logger.info("Cleared YOLO runs cache")


# ── Schemas ───────────────────────────────────────────────────────────────────
class InferRequest(BaseModel):
    image_base64: str


class TensorRequest(BaseModel):
    tensor_base64:  str
    patch_wsi_size: int = 448
    overlap_px:     int = 24
    edge_left:      bool = False
    edge_top:       bool = False
    edge_right:     bool = False
    edge_bottom:    bool = False


class PatchItem(BaseModel):
    patch_id:       str
    tensor_base64:  str
    patch_wsi_size: int = 448
    overlap_px:     int = 24
    edge_left:      bool = False
    edge_top:       bool = False
    edge_right:     bool = False
    edge_bottom:    bool = False


class BatchTensorRequest(BaseModel):
    patches: List[PatchItem]


# ── Preprocess: ВАЖНО — YOLO/Ultralytics ожидает ТОЛЬКО /255, без ImageNet ────
def preprocess_hwc(hwc: np.ndarray) -> np.ndarray:
    """RGB HWC uint8 [0..255] → NCHW float32 в диапазоне 0..1.
    YOLO Ultralytics не использует ImageNet mean/std."""
    f = hwc.astype(np.float32) * (1.0 / 255.0)
    return np.ascontiguousarray(f.transpose(2, 0, 1)[np.newaxis])


# ── NMS (учитываем, что у seg-модели первые 4+nc каналов — это detect) ───────
def _sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(x, -88, 88)))


def run_nms(output: np.ndarray) -> list:
    # output: (1, 4+nc[+32], anchors). Берём только detect-часть.
    preds = output[0]
    coords = preds[:4, :].T            # (anchors, 4) xywh в пикселях входа
    scores = preds[4:4 + NUM_CLASSES]  # (nc, anchors) — берём ТОЛЬКО classes,
                                       # не mask coefficients

    if scores.size == 0:
        return []

    if scores.max() > 1.0:
        scores = _sigmoid(scores)

    cls_ids    = scores.argmax(axis=0)
    cls_scores = scores.max(axis=0)

    mask = cls_scores > CONF_THRESH
    coords  = coords[mask]
    cls_ids = cls_ids[mask]
    confs   = cls_scores[mask]
    if len(coords) == 0:
        return []

    x1 = coords[:, 0] - coords[:, 2] / 2
    y1 = coords[:, 1] - coords[:, 3] / 2
    x2 = coords[:, 0] + coords[:, 2] / 2
    y2 = coords[:, 1] + coords[:, 3] / 2

    valid = ((coords[:, 2] > 2) & (coords[:, 3] > 2) &
             (coords[:, 2] < INPUT_SIZE * 0.9) &
             (coords[:, 3] < INPUT_SIZE * 0.9) &
             (x2 > 0) & (y2 > 0) &
             (x1 < INPUT_SIZE) & (y1 < INPUT_SIZE))
    x1, y1, x2, y2 = x1[valid], y1[valid], x2[valid], y2[valid]
    cls_ids = cls_ids[valid]
    confs   = confs[valid]
    if len(x1) == 0:
        return []

    xyxy = np.stack([x1, y1, x2, y2], axis=1).astype(np.float32)
    indices = cv2.dnn.NMSBoxes(xyxy.tolist(), confs.tolist(),
                               CONF_THRESH, IOU_THRESH)
    if len(indices) == 0:
        return []

    return [{
        "cls_id": int(cls_ids[int(i)]),
        "cx":   float((x1[int(i)] + x2[int(i)]) / 2),
        "cy":   float((y1[int(i)] + y2[int(i)]) / 2),
        "x1":   float(x1[int(i)]), "y1": float(y1[int(i)]),
        "x2":   float(x2[int(i)]), "y2": float(y2[int(i)]),
        "conf": float(confs[int(i)]),
    } for i in indices]


def apply_overlap_filter(dets: list, meta) -> dict:
    half_ov = meta.overlap_px / 2
    lo_x, lo_y = half_ov, half_ov
    hi_x = meta.patch_wsi_size - half_ov
    hi_y = meta.patch_wsi_size - half_ov
    eos_count = eosg_count = valid_eos = valid_eosg = 0
    for d in dets:
        name = CLASS_NAMES.get(d["cls_id"], "unknown")
        if name == "eos":   eos_count += 1
        elif name == "eosg": eosg_count += 1
        if lo_x <= d["cx"] <= hi_x and lo_y <= d["cy"] <= hi_y:
            if name == "eos":   valid_eos += 1
            elif name == "eosg": valid_eosg += 1
    return {
        "patch_id":    getattr(meta, "patch_id", "single"),
        "total_count": eos_count + eosg_count,
        "valid_count": valid_eos + valid_eosg,
        "valid_eos":   valid_eos,
        "valid_eosg":  valid_eosg,
    }


# ── Низкоуровневый запуск, всё через GPU_EXECUTOR (1 поток) ──────────────────
def _run_blocking(nchw: np.ndarray) -> np.ndarray:
    return sess.run([output_name], {input_name: nchw})[0]


async def run_session(nchw: np.ndarray) -> np.ndarray:
    loop = asyncio.get_event_loop()
    try:
        return await loop.run_in_executor(GPU_EXECUTOR, _run_blocking, nchw)
    except Exception as e:
        async with _session_lock:
            _maybe_recreate_session(e)
        raise


# ── Endpoints ─────────────────────────────────────────────────────────────────
@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_PATH,
            "providers": sess.get_providers(),
            "input_shape":  sess.get_inputs()[0].shape,
            "output_shape": sess.get_outputs()[0].shape,
            "num_outputs":  len(sess.get_outputs()),
            "conf_thresh":  CONF_THRESH,
            "num_classes":  NUM_CLASSES}


@app.post("/infer")
async def infer(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    img_rgb = np.array(pil_img)
    resized = cv2.resize(img_rgb, (INPUT_SIZE, INPUT_SIZE))
    nchw = preprocess_hwc(resized)
    try:
        output = await run_session(nchw)
    except Exception as e:
        raise HTTPException(500, f"Inference failed: {e}")
    dets = run_nms(output)
    return {"eosinophil_count": len(dets),
            "boxes": [{"x1": d["x1"], "y1": d["y1"],
                       "x2": d["x2"], "y2": d["y2"],
                       "conf": d["conf"]} for d in dets]}


@app.post("/infer_raw")
async def infer_raw(req: TensorRequest):
    raw = base64.b64decode(req.tensor_base64)
    hwc = np.frombuffer(raw, dtype=np.uint8).reshape(
        INPUT_SIZE, INPUT_SIZE, 3).copy()
    mean_val = float(hwc.mean())
    if mean_val > WHITE_THRESH:
        return apply_overlap_filter([], req)

    nchw = preprocess_hwc(hwc)
    try:
        output = await run_session(nchw)
    except Exception as e:
        raise HTTPException(500, f"Inference failed: {e}")

    dets = run_nms(output)
    return apply_overlap_filter(dets, req)


@app.post("/infer_batch")
async def infer_batch(req: BatchTensorRequest):
    results = []
    for patch in req.patches:
        raw = base64.b64decode(patch.tensor_base64)
        hwc = np.frombuffer(raw, dtype=np.uint8).reshape(
            INPUT_SIZE, INPUT_SIZE, 3).copy()
        if float(hwc.mean()) > WHITE_THRESH:
            results.append({"patch_id": patch.patch_id,
                            "total_count": 0, "valid_count": 0,
                            "valid_eos": 0, "valid_eosg": 0})
            continue
        nchw = preprocess_hwc(hwc)
        try:
            output = await run_session(nchw)
        except Exception as e:
            logger.error(f"patch {patch.patch_id} failed: {e}")
            results.append({"patch_id": patch.patch_id,
                            "total_count": 0, "valid_count": 0,
                            "valid_eos": 0, "valid_eosg": 0})
            continue
        dets = run_nms(output)
        results.append(apply_overlap_filter(dets, patch))
    return {"results": results}


@app.post("/debug_raw")
async def debug_raw(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    img_rgb = np.array(pil_img)
    resized = cv2.resize(img_rgb, (INPUT_SIZE, INPUT_SIZE))
    nchw = preprocess_hwc(resized)
    output = await run_session(nchw)
    scores = output[0][4:4 + NUM_CLASSES, :]
    return {
        "output_shape":   list(output.shape),
        "num_outputs":    len(sess.get_outputs()),
        "scores_min":     float(scores.min()),
        "scores_max":     float(scores.max()),
        "scores_gt_0.25": int((scores.max(axis=0) > 0.25).sum()),
        "scores_gt_0.5":  int((scores.max(axis=0) > 0.5).sum()),
    }