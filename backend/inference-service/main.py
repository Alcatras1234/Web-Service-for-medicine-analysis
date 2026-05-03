import asyncio
import base64
import io
import logging
import os
import shutil
from typing import List

import cv2
import numpy as np
import onnxruntime as ort
from fastapi import FastAPI
from PIL import Image
from pydantic import BaseModel

# ── Auto-fix cuDNN/cuBLAS PATH на Windows ────────────────────────────────────
_site = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "inference-venv", "Lib", "site-packages", "nvidia")
for _pkg in ("cudnn", "cublas", "cuda_runtime", "cufft", "curand"):
    _bin = os.path.normpath(os.path.join(_site, _pkg, "bin"))
    if os.path.isdir(_bin):
        os.environ["PATH"] = _bin + os.pathsep + os.environ.get("PATH", "")

# ── Logging ───────────────────────────────────────────────────────────────────
logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(levelname)s  %(message)s")
logger = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────
MODEL_PATH  = os.environ.get("MODEL_PATH",  "./triton-models/eosin_yolo/1/best.onnx")
CONF_THRESH = float(os.environ.get("CONF_THRESH", "0.25"))
IOU_THRESH  = float(os.environ.get("IOU_THRESH",  "0.45"))
INPUT_SIZE  = int(os.environ.get("INPUT_SIZE",    "448"))
CONCURRENCY = int(os.environ.get("CONCURRENCY",   "4"))

# ── Preprocessing constants ───────────────────────────────────────────────────
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32).reshape(1, 1, 3)
IMAGENET_STD  = np.array([0.229, 0.224, 0.225], dtype=np.float32).reshape(1, 1, 3)
WHITE_THRESH  = float(os.environ.get("WHITE_THRESH", "240"))  # uint8 [0..255]

# ── ONNX Runtime ──────────────────────────────────────────────────────────────
_available = ort.get_available_providers()
logger.info(f"Available ORT providers: {_available}")

if "CUDAExecutionProvider" not in _available:
    raise RuntimeError("CUDAExecutionProvider not available.")

providers = [(
    "CUDAExecutionProvider",
    {"cudnn_conv_use_max_workspace": "1", "do_copy_in_default_stream": True},
)]

sess_opts = ort.SessionOptions()
sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
sess_opts.intra_op_num_threads      = 1
sess_opts.execution_mode            = ort.ExecutionMode.ORT_SEQUENTIAL

sess        = ort.InferenceSession(MODEL_PATH, sess_options=sess_opts, providers=providers)
input_name  = sess.get_inputs()[0].name
output_name = sess.get_outputs()[0].name

_inp = sess.get_inputs()[0]
_out = sess.get_outputs()[0]
logger.info(f"Input:  name={_inp.name}  shape={_inp.shape}")
logger.info(f"Output: name={_out.name}  shape={_out.shape}")

CLASS_NAMES = {0: "eos", 1: "eosg"}
logger.info(f"Model loaded: {MODEL_PATH}  providers={sess.get_providers()}")

# ── FastAPI ───────────────────────────────────────────────────────────────────
app  = FastAPI()
_sem = asyncio.Semaphore(CONCURRENCY)

@app.on_event("startup")
def cleanup_runs():
    shutil.rmtree("runs", ignore_errors=True)
    logger.info("Cleared YOLO runs cache")

# ── Schemas ───────────────────────────────────────────────────────────────────
class InferRequest(BaseModel):
    image_base64: str

class TensorRequest(BaseModel):
    tensor_base64:  str
    patch_wsi_size: int  = 448
    overlap_px:     int  = 24
    edge_left:      bool = False
    edge_top:       bool = False
    edge_right:     bool = False
    edge_bottom:    bool = False

class PatchItem(BaseModel):
    patch_id:       str
    tensor_base64:  str
    patch_wsi_size: int  = 448
    overlap_px:     int  = 24
    edge_left:      bool = False
    edge_top:       bool = False
    edge_right:     bool = False
    edge_bottom:    bool = False

class BatchTensorRequest(BaseModel):
    patches: List[PatchItem]

# ── NMS ───────────────────────────────────────────────────────────────────────
def _sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(x, -88, 88)))

def run_nms(output: np.ndarray) -> list:
    preds      = output[0]           # (4+C, 8400)
    coords     = preds[:4, :].T      # (8400, 4) xywh абс.
    scores     = preds[4:, :]        # (C, 8400)

    if scores.max() > 1.0:
        scores = _sigmoid(scores)

    cls_ids    = scores.argmax(axis=0)
    cls_scores = scores.max(axis=0)

    mask    = cls_scores > CONF_THRESH
    coords  = coords[mask]
    cls_ids = cls_ids[mask]
    confs   = cls_scores[mask]

    if len(coords) == 0:
        return []

    x1 = coords[:, 0] - coords[:, 2] / 2
    y1 = coords[:, 1] - coords[:, 3] / 2
    x2 = coords[:, 0] + coords[:, 2] / 2
    y2 = coords[:, 1] + coords[:, 3] / 2

    valid = (
        (coords[:, 2] > 2) & (coords[:, 3] > 2) &
        (coords[:, 2] < INPUT_SIZE * 0.9) & (coords[:, 3] < INPUT_SIZE * 0.9) &
        (x2 > 0) & (y2 > 0) &
        (x1 < INPUT_SIZE) & (y1 < INPUT_SIZE)
    )
    x1, y1, x2, y2 = x1[valid], y1[valid], x2[valid], y2[valid]
    cls_ids = cls_ids[valid]
    confs   = confs[valid]

    if len(x1) == 0:
        return []

    xyxy    = np.stack([x1, y1, x2, y2], axis=1).astype(np.float32)
    indices = cv2.dnn.NMSBoxes(xyxy.tolist(), confs.tolist(), CONF_THRESH, IOU_THRESH)

    if len(indices) == 0:
        return []

    return [
        {
            "cls_id": int(cls_ids[int(i)]),
            "cx":     float((x1[int(i)] + x2[int(i)]) / 2),
            "cy":     float((y1[int(i)] + y2[int(i)]) / 2),
            "x1":     float(x1[int(i)]),
            "y1":     float(y1[int(i)]),
            "x2":     float(x2[int(i)]),
            "y2":     float(y2[int(i)]),
            "conf":   float(confs[int(i)]),
        }
        for i in indices
    ]

# ── Overlap zone фильтрация ───────────────────────────────────────────────────
def apply_overlap_filter(dets: list, meta) -> dict:
    half_ov = meta.overlap_px / 2
    lo_x = half_ov
    lo_y = half_ov
    hi_x = meta.patch_wsi_size - half_ov
    hi_y = meta.patch_wsi_size - half_ov

    eos_count = eosg_count = valid_eos = valid_eosg = 0
    for d in dets:
        name = CLASS_NAMES.get(d["cls_id"], "unknown")
        if name == "eos":    eos_count  += 1
        elif name == "eosg": eosg_count += 1
        if lo_x <= d["cx"] <= hi_x and lo_y <= d["cy"] <= hi_y:
            if name == "eos":    valid_eos  += 1
            elif name == "eosg": valid_eosg += 1

    return {
        "patch_id":    getattr(meta, "patch_id", "single"),
        "total_count": eos_count + eosg_count,
        "valid_count": valid_eos + valid_eosg,
        "valid_eos":   valid_eos,
        "valid_eosg":  valid_eosg,
    }

# ── Общий препроцессинг RGB HWC uint8 → NCHW float32 ─────────────────────────
def preprocess_hwc(hwc: np.ndarray) -> np.ndarray:
    """RGB HWC uint8 [0..255] → NCHW float32, ImageNet normalized"""
    hwc_f = hwc.astype(np.float32) / 255.0
    hwc_n = (hwc_f - IMAGENET_MEAN) / IMAGENET_STD
    return hwc_n.transpose(2, 0, 1)[np.newaxis].astype(np.float32)  # (1, 3, H, W)

# ── GPU inference ─────────────────────────────────────────────────────────────
def _run_single(nchw: np.ndarray) -> np.ndarray:
    return sess.run([output_name], {input_name: nchw})[0]

def _run_single_patch(patch: PatchItem) -> dict:
    raw = base64.b64decode(patch.tensor_base64)

    # Java шлёт RGB HWC uint8: [H*W*3] байт
    hwc = np.frombuffer(raw, dtype=np.uint8).reshape(INPUT_SIZE, INPUT_SIZE, 3).copy()

    mean_val = float(hwc.mean())
    logger.info(f"patch={patch.patch_id[:8]} mean={mean_val:.1f} skip={mean_val > WHITE_THRESH}")

    if mean_val > WHITE_THRESH:
        return {"patch_id": patch.patch_id, "total_count": 0,
                "valid_count": 0, "valid_eos": 0, "valid_eosg": 0}

    nchw   = preprocess_hwc(hwc)
    output = sess.run([output_name], {input_name: nchw})[0]
    dets   = run_nms(output)
    return apply_overlap_filter(dets, patch)

def _run_batch(patches: List[PatchItem]) -> list:
    results = []
    for patch in patches:
        result = _run_single_patch(patch)
        logger.info(
            f"patch={patch.patch_id} total={result['total_count']} valid={result['valid_count']}"
        )
        results.append(result)
    return results

# ── Endpoints ─────────────────────────────────────────────────────────────────
@app.get("/health")
def health():
    return {
        "status":       "ok",
        "model":        MODEL_PATH,
        "providers":    sess.get_providers(),
        "input_shape":  sess.get_inputs()[0].shape,
        "output_shape": sess.get_outputs()[0].shape,
        "conf_thresh":  CONF_THRESH,
    }

@app.post("/infer")
async def infer(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img   = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    img_rgb   = np.array(pil_img)
    resized   = cv2.resize(img_rgb, (INPUT_SIZE, INPUT_SIZE))

    # FIX: ImageNet нормализация (была только /255.0)
    nchw = preprocess_hwc(resized)

    async with _sem:
        loop   = asyncio.get_event_loop()
        output = await loop.run_in_executor(None, _run_single, nchw)

    dets  = run_nms(output)
    boxes = [{"x1": d["x1"], "y1": d["y1"],
               "x2": d["x2"], "y2": d["y2"], "conf": d["conf"]}
             for d in dets]
    return {"eosinophil_count": len(dets), "boxes": boxes}

@app.post("/infer_raw")
async def infer_raw(req: TensorRequest):
    raw = base64.b64decode(req.tensor_base64)

    # FIX: Java теперь шлёт uint8 HWC, не float32 CHW
    hwc = np.frombuffer(raw, dtype=np.uint8).reshape(INPUT_SIZE, INPUT_SIZE, 3).copy()

    mean_val = float(hwc.mean())
    logger.info(f"[infer_raw] mean={mean_val:.1f} skip={mean_val > WHITE_THRESH}")

    if mean_val > WHITE_THRESH:
        meta = PatchItem(patch_id="single", tensor_base64=req.tensor_base64,
                         patch_wsi_size=req.patch_wsi_size, overlap_px=req.overlap_px,
                         edge_left=req.edge_left, edge_top=req.edge_top,
                         edge_right=req.edge_right, edge_bottom=req.edge_bottom)
        return apply_overlap_filter([], meta)

    nchw = preprocess_hwc(hwc)

    async with _sem:
        loop   = asyncio.get_event_loop()
        output = await loop.run_in_executor(None, _run_single, nchw)

    meta   = PatchItem(patch_id="single", tensor_base64=req.tensor_base64,
                       patch_wsi_size=req.patch_wsi_size, overlap_px=req.overlap_px,
                       edge_left=req.edge_left, edge_top=req.edge_top,
                       edge_right=req.edge_right, edge_bottom=req.edge_bottom)
    dets   = run_nms(output)
    result = apply_overlap_filter(dets, meta)

    logger.debug(f"[DEBUG] scores_max={output[0][4:, :].max():.4f} "
                 f"dets={len(dets)} valid={result['valid_count']}")
    return result

@app.post("/infer_batch")
async def infer_batch(req: BatchTensorRequest):
    async with _sem:
        loop    = asyncio.get_event_loop()
        results = await loop.run_in_executor(None, _run_batch, req.patches)
    return {"results": results}

# ── Debug endpoint ────────────────────────────────────────────────────────────
@app.post("/debug_raw")
async def debug_raw(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img   = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    img_rgb   = np.array(pil_img)
    resized   = cv2.resize(img_rgb, (INPUT_SIZE, INPUT_SIZE))
    nchw      = preprocess_hwc(resized)

    output = sess.run([output_name], {input_name: nchw})[0]
    scores = output[0][4:, :]

    return {
        "output_shape":       list(output.shape),
        "scores_min":         float(scores.min()),
        "scores_max":         float(scores.max()),
        "scores_gt_0.25":     int((scores.max(axis=0) > 0.25).sum()),
        "scores_gt_0.5":      int((scores.max(axis=0) > 0.5).sum()),
        "coords_sample_xywh": output[0][:4, :5].tolist(),
    }