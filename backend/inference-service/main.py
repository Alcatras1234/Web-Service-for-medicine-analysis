import asyncio
import base64
import io
import logging
import os
import shutil
import struct

import cv2
import numpy as np
import onnxruntime as ort
from fastapi import FastAPI
from PIL import Image
from pydantic import BaseModel

import os

# ── Auto-fix cuDNN/cuBLAS PATH на Windows ────────────────────────────────────
_site = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "inference-venv", "Lib", "site-packages", "nvidia")
for _pkg in ("cudnn", "cublas", "cuda_runtime", "cufft", "curand"):
    _bin = os.path.normpath(os.path.join(_site, _pkg, "bin"))
    if os.path.isdir(_bin):
        os.environ["PATH"] = _bin + os.pathsep + os.environ.get("PATH", "")
# ─────────────────────────────────────────────────────────────────────────────

# ── Logging ───────────────────────────────────────────────────────────────────
logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(levelname)s  %(message)s")
logger = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────
MODEL_PATH  = os.environ.get("MODEL_PATH", "./triton-models/eosin_yolo/1/best.onnx")
CONF_THRESH = float(os.environ.get("CONF_THRESH", "0.15"))
IOU_THRESH  = float(os.environ.get("IOU_THRESH",  "0.5"))
INPUT_SIZE  = int(os.environ.get("INPUT_SIZE",    "448"))
CONCURRENCY = int(os.environ.get("CONCURRENCY",   "8"))

# ── ONNX Runtime + Tensor Cores ───────────────────────────────────────────────
# TensorRT EP использует Tensor Cores автоматически (FP16/INT8)
# CUDAExecutionProvider тоже задействует их через cuDNN

_available = ort.get_available_providers()
logger.info(f"Available ORT providers: {_available}")

# Пробуем TensorRT → CUDA → CPU (в порядке приоритета)
if "TensorrtExecutionProvider" in _available:
    providers = [
        (
            "TensorrtExecutionProvider",
            {
                "trt_fp16_enable": True,          # FP16 → Tensor Cores
                "trt_max_workspace_size": 1 << 30, # 1 GB
            },
        ),
        (
            "CUDAExecutionProvider",
            {"cudnn_conv_use_max_workspace": "1"},
        ),
        "CPUExecutionProvider",
    ]
    logger.info("Using TensorRT EP (FP16 Tensor Cores)")
elif "CUDAExecutionProvider" in _available:
    providers = [
        (
            "CUDAExecutionProvider",
            {
                "cudnn_conv_use_max_workspace": "1",
                "do_copy_in_default_stream": True,
            },
        ),
        "CPUExecutionProvider",
    ]
    logger.info("Using CUDA EP")
else:
    logger.warning("GPU not available")

sess_opts = ort.SessionOptions()
sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
sess_opts.intra_op_num_threads = 4

sess        = ort.InferenceSession(MODEL_PATH, sess_options=sess_opts, providers=providers)
input_name  = sess.get_inputs()[0].name
output_name = sess.get_outputs()[0].name

# Имена классов — YOLO11 seg ONNX не хранит names, задаём вручную
# Порядок должен совпадать с тем, как экспортировалась модель
CLASS_NAMES = {0: "eos", 1: "eosg"}

logger.info(f"Model loaded: {MODEL_PATH}")
logger.info(f"Active providers: {sess.get_providers()}")
logger.info(f"Classes: {CLASS_NAMES}")

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

# ── Preprocessing ─────────────────────────────────────────────────────────────
def to_nchw(img_bgr: np.ndarray) -> np.ndarray:
    """uint8 HWC BGR → float32 NCHW [0..1] resized to INPUT_SIZE"""
    resized = cv2.resize(img_bgr, (INPUT_SIZE, INPUT_SIZE))
    rgb     = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
    nchw    = rgb.transpose(2, 0, 1).astype(np.float32) / 255.0
    return nchw[np.newaxis, ...]  # (1, 3, H, W)

# ── NMS ───────────────────────────────────────────────────────────────────────
def run_nms(output: np.ndarray):
    """
    YOLO11 detect ONNX output shape: (1, num_classes+4, 8400)
    Returns list of (cls_id, cx, cy, conf)
    """
    preds  = output[0]                          # (C+4, 8400)
    coords = preds[:4, :].T                     # (8400, 4) xywh
    scores = preds[4:, :]                       # (num_cls, 8400)

    cls_ids     = scores.argmax(axis=0)         # (8400,)
    cls_scores  = scores.max(axis=0)            # (8400,)

    mask   = cls_scores > CONF_THRESH
    coords  = coords[mask]
    cls_ids = cls_ids[mask]
    confs   = cls_scores[mask]

    if len(coords) == 0:
        return []

    x1 = coords[:, 0] - coords[:, 2] / 2
    y1 = coords[:, 1] - coords[:, 3] / 2
    x2 = coords[:, 0] + coords[:, 2] / 2
    y2 = coords[:, 1] + coords[:, 3] / 2
    xyxy = np.stack([x1, y1, x2, y2], axis=1).astype(np.float32)

    indices = cv2.dnn.NMSBoxes(
        xyxy.tolist(), confs.tolist(), CONF_THRESH, IOU_THRESH
    )
    if len(indices) == 0:
        return []

    detections = []
    for i in indices:
        idx = int(i)
        cx  = float((x1[idx] + x2[idx]) / 2)
        cy  = float((y1[idx] + y2[idx]) / 2)
        detections.append({
            "cls_id": int(cls_ids[idx]),
            "cx":     cx,
            "cy":     cy,
            "x1":     float(x1[idx]),
            "y1":     float(y1[idx]),
            "x2":     float(x2[idx]),
            "y2":     float(y2[idx]),
            "conf":   float(confs[idx]),
        })
    return detections

# ── Raw inference ─────────────────────────────────────────────────────────────
def _run_sess(nchw: np.ndarray) -> np.ndarray:
    return sess.run([output_name], {input_name: nchw})[0]

# ── Endpoints ─────────────────────────────────────────────────────────────────
@app.get("/health")
def health():
    return {
        "status":    "ok",
        "model":     MODEL_PATH,
        "providers": sess.get_providers(),
    }

@app.post("/infer")
async def infer(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img   = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    img_bgr   = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
    nchw      = to_nchw(img_bgr)

    async with _sem:
        loop   = asyncio.get_event_loop()
        output = await loop.run_in_executor(None, _run_sess, nchw)

    dets   = run_nms(output)
    count  = len(dets)
    boxes  = [
        {"x1": d["x1"], "y1": d["y1"], "x2": d["x2"], "y2": d["y2"], "conf": d["conf"]}
        for d in dets
    ]
    return {"eosinophil_count": count, "boxes": boxes}

@app.post("/infer_raw")
async def infer_raw(req: TensorRequest):
    # Java шлёт NCHW float32 little-endian (1,3,448,448)
    raw    = base64.b64decode(req.tensor_base64)
    nchw   = np.frombuffer(raw, dtype=np.float32).reshape(1, 3, INPUT_SIZE, INPUT_SIZE).copy()

    async with _sem:
        loop   = asyncio.get_event_loop()
        output = await loop.run_in_executor(None, _run_sess, nchw)

    dets = run_nms(output)

    # Overlap zone фильтрация
    half_ov = req.overlap_px / 2
    lo_x = half_ov               if not req.edge_left   else 0.0
    lo_y = half_ov               if not req.edge_top    else 0.0
    hi_x = (req.patch_wsi_size - half_ov) if not req.edge_right  else float(req.patch_wsi_size)
    hi_y = (req.patch_wsi_size - half_ov) if not req.edge_bottom else float(req.patch_wsi_size)

    eos_count = eosg_count = valid_eos = valid_eosg = 0

    for d in dets:
        name = CLASS_NAMES.get(d["cls_id"], "unknown")
        if name == "eos":    eos_count  += 1
        elif name == "eosg": eosg_count += 1

        if lo_x <= d["cx"] <= hi_x and lo_y <= d["cy"] <= hi_y:
            if name == "eos":    valid_eos  += 1
            elif name == "eosg": valid_eosg += 1

    total_count = eos_count  + eosg_count
    valid_count = valid_eos  + valid_eosg

    logger.info(
        f"total=({eos_count}eos+{eosg_count}eosg={total_count}) "
        f"valid=({valid_eos}eos+{valid_eosg}eosg={valid_count}) "
        f"zone=({lo_x:.0f}-{hi_x:.0f},{lo_y:.0f}-{hi_y:.0f})"
    )

    return {
        "eosinophil_count": valid_count,
        "total_count":      total_count,
        "valid_count":      valid_count,
        "valid_eos":        valid_eos,
        "valid_eosg":       valid_eosg,
    }