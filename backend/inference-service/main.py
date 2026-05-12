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
det_output_name = sess.get_outputs()[0].name           # detect head
out_shape       = sess.get_outputs()[0].shape
all_outputs     = [o.name for o in sess.get_outputs()]
logger.info(f"ONNX input  {input_name} shape={sess.get_inputs()[0].shape}")
logger.info(f"ONNX outputs: {all_outputs} (head shape={out_shape})")

# §3.1: определяем тип модели — detect (4+nc каналов) или seg (4+nc+32 + второй output с proto-масками)
expected_detect = 4 + NUM_CLASSES
expected_seg    = 4 + NUM_CLASSES + 32
ch = out_shape[1] if isinstance(out_shape[1], int) else None

IS_SEG_MODEL = (ch == expected_seg) and (len(sess.get_outputs()) >= 2)
proto_output_name = None
if IS_SEG_MODEL:
    proto_output_name = sess.get_outputs()[1].name
    proto_shape = sess.get_outputs()[1].shape
    logger.info(f"✓ SEGMENTATION MODEL detected. Proto output '{proto_output_name}' shape={proto_shape}. "
                f"Подсчёт через маски + connected components.")
else:
    if ch == expected_detect:
        logger.info(f"✓ DETECTION-only model. Подсчёт по bbox (после NMS).")
    else:
        logger.warning(
            f"!!! ONNX output channels = {ch}, ожидали {expected_detect} (detect) "
            f"или {expected_seg} (seg) для NUM_CLASSES={NUM_CLASSES}. "
            f"Скорее всего в best.onnx лежит pretrained модель на COCO (80 классов). "
            f"Перевыгрузите дообученную модель через `model.export(format='onnx')` "
            f"из чекпоинта вашего fine-tune."
        )

CLASS_NAMES = {0: "eos", 1: "eosg"}

# E6: версия модели — отдаём её клиенту в каждом ответе для аудита
MODEL_VERSION = os.environ.get(
    "MODEL_VERSION",
    f"yolo11s-seg/{os.path.basename(MODEL_PATH)}"
)

# Однопоточный executor для GPU инференса — гарантирует, что в один и тот же
# момент только один тред работает с CUDA stream сессии.
GPU_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="ort-gpu")
# Лок на пересоздание сессии после сбоя CUDA
_session_lock = asyncio.Lock()


def _maybe_recreate_session(err: Exception):
    """После CUDA-700 контекст драйвера отравлен — пересоздаём сессию."""
    global sess, input_name, det_output_name, proto_output_name
    msg = str(err)
    if "CUDA" in msg or "cudnn" in msg or "illegal memory access" in msg:
        logger.error("CUDA error detected, recreating ORT session...")
        try:
            sess = _build_session()
            input_name  = sess.get_inputs()[0].name
            det_output_name = sess.get_outputs()[0].name
            if IS_SEG_MODEL and len(sess.get_outputs()) >= 2:
                proto_output_name = sess.get_outputs()[1].name
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


def run_nms(output: np.ndarray, proto: np.ndarray = None) -> list:
    """
    Возвращает список детекций. У seg-модели каждая детекция содержит
    bbox (от YOLO) + connected-components count внутри её маски (для разделения
    слипшихся клеток в одном bbox'е).

    output: (1, 4+nc[+32], anchors). Берём только detect-часть для NMS.
    proto:  (1, 32, mh, mw) — proto-маски, только если seg-модель.
    """
    preds = output[0]                    # (4+nc[+32], anchors)
    coords = preds[:4, :].T              # (anchors, 4) xywh
    scores = preds[4:4 + NUM_CLASSES]    # (nc, anchors)

    if scores.size == 0:
        return []
    if scores.max() > 1.0:
        scores = _sigmoid(scores)

    cls_ids    = scores.argmax(axis=0)
    cls_scores = scores.max(axis=0)

    has_masks = IS_SEG_MODEL and proto is not None and preds.shape[0] >= 4 + NUM_CLASSES + 32
    mask_coefs = preds[4 + NUM_CLASSES:4 + NUM_CLASSES + 32].T if has_masks else None  # (anchors, 32)

    mask_above = cls_scores > CONF_THRESH
    coords  = coords[mask_above]
    cls_ids = cls_ids[mask_above]
    confs   = cls_scores[mask_above]
    if has_masks:
        mask_coefs = mask_coefs[mask_above]
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
    if has_masks:
        mask_coefs = mask_coefs[valid]
    if len(x1) == 0:
        return []

    xyxy = np.stack([x1, y1, x2, y2], axis=1).astype(np.float32)
    indices = cv2.dnn.NMSBoxes(xyxy.tolist(), confs.tolist(),
                               CONF_THRESH, IOU_THRESH)
    if len(indices) == 0:
        return []

    # ── §3.1: connected components на masks для split'a слипшихся клеток ──────
    cc_per_det = None
    if has_masks:
        cc_per_det = _compute_cc_counts(
            proto, mask_coefs, [int(i) for i in indices],
            x1, y1, x2, y2, INPUT_SIZE
        )

    out = []
    for k, i in enumerate(indices):
        ii = int(i)
        det = {
            "cls_id": int(cls_ids[ii]),
            "cx":   float((x1[ii] + x2[ii]) / 2),
            "cy":   float((y1[ii] + y2[ii]) / 2),
            "x1":   float(x1[ii]), "y1": float(y1[ii]),
            "x2":   float(x2[ii]), "y2": float(y2[ii]),
            "conf": float(confs[ii]),
        }
        if cc_per_det is not None:
            det["cc"] = int(cc_per_det[k])     # сколько отдельных компонент в маске этого bbox'а
        out.append(det)
    return out


def _compute_cc_counts(proto, mask_coefs_kept, kept_idx, x1, y1, x2, y2, input_size):
    """
    Для каждого bbox после NMS:
      1. Строим маску = sigmoid(coefs @ proto.flatten)
      2. Crop по bbox
      3. Threshold > 0.5 → бинарка
      4. cv2.connectedComponents → число отдельных клеток в маске
    Возвращает np.array[len(kept_idx)].
    """
    p = proto[0]                          # (32, mh, mw)
    nm, mh, mw = p.shape
    proto_flat = p.reshape(nm, mh * mw).astype(np.float32)
    sx = mw / input_size
    sy = mh / input_size

    counts = np.ones(len(kept_idx), dtype=np.int32)  # дефолт = 1 (одна клетка на bbox)
    for k, ii in enumerate(kept_idx):
        coefs = mask_coefs_kept[k].astype(np.float32)            # (32,)
        m = _sigmoid(coefs @ proto_flat).reshape(mh, mw)         # (mh, mw)
        # crop в координатах маски
        mx1 = max(0, int(x1[ii] * sx))
        my1 = max(0, int(y1[ii] * sy))
        mx2 = min(mw, int(np.ceil(x2[ii] * sx)))
        my2 = min(mh, int(np.ceil(y2[ii] * sy)))
        if mx2 - mx1 < 2 or my2 - my1 < 2:
            continue
        crop = m[my1:my2, mx1:mx2]
        binary = (crop > 0.5).astype(np.uint8)
        if binary.sum() == 0:
            continue
        n_components, _ = cv2.connectedComponents(binary)
        # cv2 возвращает кол-во компонент включая фон
        counts[k] = max(1, n_components - 1)
    return counts


def apply_overlap_filter(dets: list, meta) -> dict:
    """
    §3.1 + §3.2: считаем клетки. Если у детекции есть `cc` (connected components
    из маски) — это число клеток внутри bbox'а (для слипшихся скоплений > 1).
    Если нет (detect-модель) — каждый bbox = 1 клетка.

    §3.4: на внешних границах WSI overlap'a нет — у соседнего патча просто нет.
    Поэтому inner-zone там расширяется до самого края патча, чтобы не было
    "мёртвой зоны" по периметру слайда (~5% потерь в old-варианте).
    """
    half_ov = meta.overlap_px / 2
    lo_x = 0.0 if meta.edge_left  else half_ov
    lo_y = 0.0 if meta.edge_top   else half_ov
    hi_x = meta.patch_wsi_size if meta.edge_right  else meta.patch_wsi_size - half_ov
    hi_y = meta.patch_wsi_size if meta.edge_bottom else meta.patch_wsi_size - half_ov
    eos_count = eosg_count = valid_eos = valid_eosg = 0
    valid_dets = []
    for d in dets:
        name = CLASS_NAMES.get(d["cls_id"], "unknown")
        # сколько клеток приходится на эту детекцию (1 если bbox-only, >=1 если seg)
        cells = int(d.get("cc", 1))
        if name == "eos":   eos_count  += cells
        elif name == "eosg": eosg_count += cells
        if lo_x <= d["cx"] <= hi_x and lo_y <= d["cy"] <= hi_y:
            if name == "eos":   valid_eos  += cells
            elif name == "eosg": valid_eosg += cells
            valid_dets.append({
                "cls": name,
                "cx": d["cx"], "cy": d["cy"],
                "x1": d["x1"], "y1": d["y1"],
                "x2": d["x2"], "y2": d["y2"],
                "conf": d["conf"],
                "cells": cells,
            })
    return {
        "patch_id":    getattr(meta, "patch_id", "single"),
        "total_count": eos_count + eosg_count,
        "valid_count": valid_eos + valid_eosg,
        "valid_eos":   valid_eos,
        "valid_eosg":  valid_eosg,
        "detections":  valid_dets,
    }


# ── Низкоуровневый запуск, всё через GPU_EXECUTOR (1 поток) ──────────────────
def _run_blocking(nchw: np.ndarray):
    """Возвращает (det, proto) если seg-модель, иначе (det, None)."""
    if IS_SEG_MODEL and proto_output_name:
        det, proto = sess.run([det_output_name, proto_output_name], {input_name: nchw})
        return det, proto
    return sess.run([det_output_name], {input_name: nchw})[0], None


async def run_session(nchw: np.ndarray):
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
            "is_seg_model": IS_SEG_MODEL,
            "model_version": MODEL_VERSION,
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
        det, proto = await run_session(nchw)
    except Exception as e:
        raise HTTPException(500, f"Inference failed: {e}")
    dets = run_nms(det, proto)
    total_cells = sum(int(d.get("cc", 1)) for d in dets)
    return {"eosinophil_count": total_cells,
            "boxes": [{"x1": d["x1"], "y1": d["y1"],
                       "x2": d["x2"], "y2": d["y2"],
                       "conf": d["conf"], "cells": int(d.get("cc", 1))} for d in dets]}


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
        det, proto = await run_session(nchw)
    except Exception as e:
        raise HTTPException(500, f"Inference failed: {e}")

    dets = run_nms(det, proto)
    return apply_overlap_filter(dets, req)


@app.post("/infer_batch")
async def infer_batch(req: BatchTensorRequest):
    results = []
    out = {"model_version": MODEL_VERSION, "results": results}
    for patch in req.patches:
        raw = base64.b64decode(patch.tensor_base64)
        hwc = np.frombuffer(raw, dtype=np.uint8).reshape(
            INPUT_SIZE, INPUT_SIZE, 3).copy()
        if float(hwc.mean()) > WHITE_THRESH:
            results.append({"patch_id": patch.patch_id,
                            "total_count": 0, "valid_count": 0,
                            "valid_eos": 0, "valid_eosg": 0,
                            "detections": []})
            continue
        nchw = preprocess_hwc(hwc)
        try:
            det, proto = await run_session(nchw)
        except Exception as e:
            logger.error(f"patch {patch.patch_id} failed: {e}")
            results.append({"patch_id": patch.patch_id,
                            "total_count": 0, "valid_count": 0,
                            "valid_eos": 0, "valid_eosg": 0,
                            "detections": []})
            continue
        dets = run_nms(det, proto)
        results.append(apply_overlap_filter(dets, patch))
    return out


@app.post("/debug_raw")
async def debug_raw(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    img_rgb = np.array(pil_img)
    resized = cv2.resize(img_rgb, (INPUT_SIZE, INPUT_SIZE))
    nchw = preprocess_hwc(resized)
    det, proto = await run_session(nchw)
    scores = det[0][4:4 + NUM_CLASSES, :]
    return {
        "is_seg_model":   IS_SEG_MODEL,
        "det_shape":      list(det.shape),
        "proto_shape":    list(proto.shape) if proto is not None else None,
        "num_outputs":    len(sess.get_outputs()),
        "scores_min":     float(scores.min()),
        "scores_max":     float(scores.max()),
        "scores_gt_0.25": int((scores.max(axis=0) > 0.25).sum()),
        "scores_gt_0.5":  int((scores.max(axis=0) > 0.5).sum()),
    }