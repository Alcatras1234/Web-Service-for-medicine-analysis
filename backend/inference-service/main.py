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
# ─────────────────────────────────────────────────────────────────────────────

# ── Logging ───────────────────────────────────────────────────────────────────
logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(levelname)s  %(message)s")
logger = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────
MODEL_PATH  = os.environ.get("MODEL_PATH",  "./triton-models/eosin_yolo/1/best.onnx")
CONF_THRESH = float(os.environ.get("CONF_THRESH", "0.15"))
IOU_THRESH  = float(os.environ.get("IOU_THRESH",  "0.5"))
INPUT_SIZE  = int(os.environ.get("INPUT_SIZE",    "448"))
CONCURRENCY = int(os.environ.get("CONCURRENCY",   "1"))   # 1 — GPU делает 1 батч за раз
BATCH_SIZE  = int(os.environ.get("BATCH_SIZE",    "8"))   # патчей в одном GPU-прогоне

# ── ONNX Runtime ──────────────────────────────────────────────────────────────
_available = ort.get_available_providers()
logger.info(f"Available ORT providers: {_available}")

if "CUDAExecutionProvider" not in _available:
    raise RuntimeError("CUDAExecutionProvider not available. Install onnxruntime-gpu + cuDNN.")

providers = [
    (
        "CUDAExecutionProvider",
        {
            "cudnn_conv_use_max_workspace": "1",
            "do_copy_in_default_stream":    True,
        },
    ),
]
logger.info("Using CUDA EP only (GPU, no CPU fallback)")

sess_opts = ort.SessionOptions()
sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
sess_opts.intra_op_num_threads      = 1
sess_opts.execution_mode            = ort.ExecutionMode.ORT_SEQUENTIAL

sess        = ort.InferenceSession(MODEL_PATH, sess_options=sess_opts, providers=providers)
input_name  = sess.get_inputs()[0].name
output_name = sess.get_outputs()[0].name

CLASS_NAMES = {0: "eos", 1: "eosg"}

logger.info(f"Model loaded: {MODEL_PATH}")
logger.info(f"Active providers: {sess.get_providers()}")

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
def run_nms(output: np.ndarray) -> list:
    """
    output shape: (1, 84, 8400)  ← один патч
    84 = 4 bbox + 80 классов
    """
    preds      = output[0]                       # (84, 8400)
    coords     = preds[:4, :].T                  # (8400, 4) xywh
    scores     = preds[4:, :]                    # (num_cls, 8400)
    cls_ids    = scores.argmax(axis=0)           # (8400,)
    cls_scores = scores.max(axis=0)              # (8400,)

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
    xyxy = np.stack([x1, y1, x2, y2], axis=1).astype(np.float32)

    indices = cv2.dnn.NMSBoxes(xyxy.tolist(), confs.tolist(), CONF_THRESH, IOU_THRESH)
    if len(indices) == 0:
        return []

    return [
        {
            "cls_id": int(cls_ids[int(i)]),
            "cx":     float((x1[int(i)] + x2[int(i)]) / 2),
            "cy":     float((y1[int(i)] + y2[int(i)]) / 2),
            "x1":     float(x1[int(i)]),  "y1": float(y1[int(i)]),
            "x2":     float(x2[int(i)]),  "y2": float(y2[int(i)]),
            "conf":   float(confs[int(i)]),
        }
        for i in indices
    ]

# ── Overlap zone фильтрация ───────────────────────────────────────────────────
def apply_overlap_filter(dets: list, meta: PatchItem) -> dict:
    half_ov = meta.overlap_px / 2
    lo_x = half_ov                         if not meta.edge_left   else 0.0
    lo_y = half_ov                         if not meta.edge_top    else 0.0
    hi_x = (meta.patch_wsi_size - half_ov) if not meta.edge_right  else float(meta.patch_wsi_size)
    hi_y = (meta.patch_wsi_size - half_ov) if not meta.edge_bottom else float(meta.patch_wsi_size)

    eos_count = eosg_count = valid_eos = valid_eosg = 0
    for d in dets:
        name = CLASS_NAMES.get(d["cls_id"], "unknown")
        if name == "eos":    eos_count  += 1
        elif name == "eosg": eosg_count += 1
        if lo_x <= d["cx"] <= hi_x and lo_y <= d["cy"] <= hi_y:
            if name == "eos":    valid_eos  += 1
            elif name == "eosg": valid_eosg += 1

    return {
        "patch_id":    meta.patch_id,
        "total_count": eos_count + eosg_count,
        "valid_count": valid_eos + valid_eosg,
        "valid_eos":   valid_eos,
        "valid_eosg":  valid_eosg,
    }

# ── GPU inference (синхронный, вызывается из executor) ────────────────────────
def _run_batch(patches: List[PatchItem]) -> list:
    n = len(patches)
    batch = np.zeros((n, 3, INPUT_SIZE, INPUT_SIZE), dtype=np.float32)

    for i, p in enumerate(patches):
        raw  = base64.b64decode(p.tensor_base64)
        nchw = np.frombuffer(raw, dtype=np.float32).reshape(3, INPUT_SIZE, INPUT_SIZE)
        batch[i] = nchw

    # Один прогон через GPU для всего батча
    outputs = sess.run([output_name], {input_name: batch})[0]  # (N, 84, 8400)

    results = []
    for i, meta in enumerate(patches):
        dets   = run_nms(outputs[i:i+1])
        result = apply_overlap_filter(dets, meta)
        results.append(result)
        logger.info(
            f"patch={meta.patch_id} "
            f"total={result['total_count']} valid={result['valid_count']}"
        )

    return results

def _run_single(nchw: np.ndarray) -> np.ndarray:
    return sess.run([output_name], {input_name: nchw})[0]

# ── Endpoints ─────────────────────────────────────────────────────────────────
@app.get("/health")
def health():
    return {
        "status":      "ok",
        "model":       MODEL_PATH,
        "providers":   sess.get_providers(),
        "batch_size":  BATCH_SIZE,
        "concurrency": CONCURRENCY,
    }

@app.post("/infer")
async def infer(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img   = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    img_bgr   = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
    resized   = cv2.resize(img_bgr, (INPUT_SIZE, INPUT_SIZE))
    rgb       = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
    nchw      = rgb.transpose(2, 0, 1).astype(np.float32)[np.newaxis] / 255.0

    async with _sem:
        loop   = asyncio.get_event_loop()
        output = await loop.run_in_executor(None, _run_single, nchw)

    dets  = run_nms(output)
    boxes = [{"x1": d["x1"], "y1": d["y1"], "x2": d["x2"], "y2": d["y2"], "conf": d["conf"]}
             for d in dets]
    return {"eosinophil_count": len(dets), "boxes": boxes}

@app.post("/infer_raw")
async def infer_raw(req: TensorRequest):
    """Одиночный патч — для совместимости и тестов."""
    raw  = base64.b64decode(req.tensor_base64)
    nchw = np.frombuffer(raw, dtype=np.float32).reshape(1, 3, INPUT_SIZE, INPUT_SIZE).copy()

    async with _sem:
        loop   = asyncio.get_event_loop()
        output = await loop.run_in_executor(None, _run_single, nchw)

    # Оборачиваем в PatchItem для единого apply_overlap_filter
    meta   = PatchItem(patch_id="single", tensor_base64=req.tensor_base64,
                       patch_wsi_size=req.patch_wsi_size, overlap_px=req.overlap_px,
                       edge_left=req.edge_left, edge_top=req.edge_top,
                       edge_right=req.edge_right, edge_bottom=req.edge_bottom)
    dets   = run_nms(output)
    result = apply_overlap_filter(dets, meta)
    return result

@app.post("/infer_batch")
async def infer_batch(req: BatchTensorRequest):
    """
    Батч патчей — один GPU-прогон для N патчей.
    Java шлёт список патчей, получает список результатов в том же порядке.
    """
    async with _sem:
        loop    = asyncio.get_event_loop()
        results = await loop.run_in_executor(None, _run_batch, req.patches)
    return {"results": results}

class BatchItem(BaseModel):
    patch_id:       str
    tensor_base64:  str
    patch_wsi_size: int  = 448
    overlap_px:     int  = 24
    edge_left:      bool = False
    edge_top:       bool = False
    edge_right:     bool = False
    edge_bottom:    bool = False

class BatchRequest(BaseModel):
    patches: list[BatchItem]

@app.post("/infer_batch")
def infer_batch(req: BatchRequest):
    results = []
    for item in req.patches:
        raw    = base64.b64decode(item.tensor_base64)
        tensor = np.frombuffer(raw, dtype=np.float32).reshape(1, 3, 448, 448)
        img    = (tensor[0].transpose(1, 2, 0) * 255).clip(0, 255).astype(np.uint8)

        r = model.predict(img, device=DEVICE, verbose=False,
                          imgsz=448, conf=0.15, iou=0.5,
                          save=False, save_txt=False)

        half_ov = item.overlap_px / 2
        lo_x = half_ov if not item.edge_left   else 0.0
        lo_y = half_ov if not item.edge_top    else 0.0
        hi_x = (item.patch_wsi_size - half_ov) if not item.edge_right  else float(item.patch_wsi_size)
        hi_y = (item.patch_wsi_size - half_ov) if not item.edge_bottom else float(item.patch_wsi_size)

        valid_eos = valid_eosg = total = 0
        if r[0].boxes is not None:
            for i, cls_id in enumerate(r[0].boxes.cls.cpu().numpy()):
                name = model.names[int(cls_id)]
                x1, y1, x2, y2 = r[0].boxes.xyxy[i].tolist()
                cx, cy = (x1+x2)/2, (y1+y2)/2
                total += 1
                if lo_x <= cx <= hi_x and lo_y <= cy <= hi_y:
                    if name == "eos":    valid_eos  += 1
                    elif name == "eosg": valid_eosg += 1

        results.append({
            "patch_id":    item.patch_id,
            "total_count": total,
            "valid_count": valid_eos + valid_eosg,
            "valid_eos":   valid_eos,
            "valid_eosg":  valid_eosg,
        })

    return {"results": results}