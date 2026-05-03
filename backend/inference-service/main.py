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

logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(levelname)s  %(message)s")
logger = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────
MODEL_PATH   = os.environ.get("MODEL_PATH",   "./triton-models/eosin_yolo/1/best.onnx")
CONF_THRESH  = float(os.environ.get("CONF_THRESH",  "0.25"))
IOU_THRESH   = float(os.environ.get("IOU_THRESH",   "0.5"))
INPUT_SIZE   = int(os.environ.get("INPUT_SIZE",     "448"))
CONCURRENCY  = int(os.environ.get("CONCURRENCY",    "1"))
BATCH_SIZE   = int(os.environ.get("BATCH_SIZE",     "8"))
WHITE_THRESH = float(os.environ.get("WHITE_THRESH", "0.94"))  # 240/255

# ── ONNX Runtime ──────────────────────────────────────────────────────────────
_available = ort.get_available_providers()
logger.info(f"Available ORT providers: {_available}")

if "CUDAExecutionProvider" not in _available:
    raise RuntimeError("CUDAExecutionProvider not available. Install onnxruntime-gpu + cuDNN.")

providers = [(
    "CUDAExecutionProvider",
    {
        "cudnn_conv_use_max_workspace": "1",
        "do_copy_in_default_stream":    True,
    },
)]

sess_opts = ort.SessionOptions()
sess_opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
sess_opts.intra_op_num_threads      = 1
sess_opts.execution_mode            = ort.ExecutionMode.ORT_SEQUENTIAL

sess        = ort.InferenceSession(MODEL_PATH, sess_options=sess_opts, providers=providers)
input_name  = sess.get_inputs()[0].name
output_name = sess.get_outputs()[0].name

# Определяем формат выхода автоматически
_out_shape = sess.get_outputs()[0].shape   # [1, 38, 4116] или [1, 84, 8400]
NUM_CLASSES = len({0: "eos", 1: "eosg"})   # 2
CLASS_NAMES = {0: "eos", 1: "eosg"}

logger.info(f"Model loaded: {MODEL_PATH}")
logger.info(f"Output shape: {_out_shape}")
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

# ── NMS — поддержка формата [1, 38, 4116] (DFL, 2 класса) ────────────────────
def run_nms(output: np.ndarray) -> list:
    """
    Поддерживает оба формата:
      - [1, 38, 4116]  — DFL YOLO11, 2 класса (eos/eosg): 4*9=36 DFL + 2 cls
      - [1, 84, 8400]  — стандарт YOLO8,  80 классов:     4 coord + 80 cls
    """
    if output is None:
        return []

    preds   = output[0]                        # (C, N)
    n_ch    = preds.shape[0]                   # 38 или 84
    reg_ch  = n_ch - NUM_CLASSES               # 36 или 82

    # ── Классовые скоры ───────────────────────────────────────────────────────
    cls_raw = preds[reg_ch:, :]                # (2, N)
    # Если DFL (reg_ch=36) — нужен sigmoid; если обычный (reg_ch=4) — уже prob
    if reg_ch > 4:
        cls_scores_all = 1.0 / (1.0 + np.exp(-cls_raw))   # sigmoid
    else:
        cls_scores_all = cls_raw

    cls_ids    = cls_scores_all.argmax(axis=0)  # (N,)
    cls_scores = cls_scores_all.max(axis=0)     # (N,)

    mask = cls_scores > CONF_THRESH
    if not mask.any():
        return []

    # ── Координаты bbox ───────────────────────────────────────────────────────
    if reg_ch > 4:
        # DFL: среднее по reg_max бинам → xywh
        reg_max = reg_ch // 4                   # 9
        reg     = preds[:reg_ch, :].reshape(4, reg_max, -1)  # (4, 9, N)
        coords  = reg.mean(axis=1).T            # (N, 4) xywh
    else:
        coords = preds[:4, :].T                 # (N, 4) xywh

    coords  = coords[mask]
    cls_ids = cls_ids[mask]
    confs   = cls_scores[mask]

    cx, cy, w, h = coords[:,0], coords[:,1], coords[:,2], coords[:,3]
    x1 = cx - w / 2;  y1 = cy - h / 2
    x2 = cx + w / 2;  y2 = cy + h / 2
    xyxy = np.stack([x1, y1, x2, y2], axis=1).astype(np.float32)

    indices = cv2.dnn.NMSBoxes(xyxy.tolist(), confs.tolist(), CONF_THRESH, IOU_THRESH)
    if len(indices) == 0:
        return []

    return [
        {
            "cls_id": int(cls_ids[int(i)]),
            "cx":     float((x1[int(i)] + x2[int(i)]) / 2),
            "cy":     float((y1[int(i)] + y2[int(i)]) / 2),
            "x1":     float(x1[int(i)]), "y1": float(y1[int(i)]),
            "x2":     float(x2[int(i)]), "y2": float(y2[int(i)]),
            "conf":   float(confs[int(i)]),
        }
        for i in indices
    ]

# ── Overlap zone фильтрация ───────────────────────────────────────────────────
def apply_overlap_filter(dets: list, meta: PatchItem) -> dict:
    if dets is None:
        dets = []

    half_ov = meta.overlap_px / 2
    lo_x = half_ov                          if not meta.edge_left   else 0.0
    lo_y = half_ov                          if not meta.edge_top    else 0.0
    hi_x = (meta.patch_wsi_size - half_ov)  if not meta.edge_right  else float(meta.patch_wsi_size)
    hi_y = (meta.patch_wsi_size - half_ov)  if not meta.edge_bottom else float(meta.patch_wsi_size)

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

# ── GPU inference ─────────────────────────────────────────────────────────────
def _run_batch(patches: List[PatchItem]) -> list:
    n     = len(patches)
    batch = np.zeros((n, 3, INPUT_SIZE, INPUT_SIZE), dtype=np.float32)

    skip_mask = []
    for i, p in enumerate(patches):
        raw  = base64.b64decode(p.tensor_base64)
        nchw = np.frombuffer(raw, dtype=np.float32).reshape(3, INPUT_SIZE, INPUT_SIZE).copy()

        # Пропуск белого фона (как в ноутбуке: mean > 240/255)
        is_white = nchw.mean() > WHITE_THRESH
        skip_mask.append(is_white)
        if is_white:
            logger.info(f"patch={p.patch_id} skipped (white background, mean={nchw.mean():.3f})")
        batch[i] = nchw

    # Один GPU-прогон для всего батча
    outputs = sess.run([output_name], {input_name: batch})[0]  # (N, 38, 4116)

    results = []
    for i, meta in enumerate(patches):
        if skip_mask[i]:
            results.append({
                "patch_id":    meta.patch_id,
                "total_count": 0,
                "valid_count": 0,
                "valid_eos":   0,
                "valid_eosg":  0,
            })
            continue

        dets   = run_nms(outputs[i:i+1])
        result = apply_overlap_filter(dets, meta)
        results.append(result)
        logger.info(
            f"patch={meta.patch_id} "
            f"total={result['total_count']} valid={result['valid_count']} "
            f"eos={result['valid_eos']} eosg={result['valid_eosg']}"
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
        "output_shape": list(_out_shape),
        "conf_thresh": CONF_THRESH,
        "batch_size":  BATCH_SIZE,
        "concurrency": CONCURRENCY,
    }

@app.post("/debug_raw")
async def debug_raw(req: TensorRequest):
    """Возвращает сырые значения выхода модели — для диагностики."""
    raw  = base64.b64decode(req.tensor_base64)
    nchw = np.frombuffer(raw, dtype=np.float32).reshape(1, 3, INPUT_SIZE, INPUT_SIZE).copy()

    async with _sem:
        loop   = asyncio.get_event_loop()
        output = await loop.run_in_executor(None, _run_single, nchw)

    preds = output[0]
    return {
        "output_shape":   list(output.shape),
        "scores_min":     float(preds.min()),
        "scores_max":     float(preds.max()),
        "scores_mean":    float(preds.mean()),
        "scores_gt_0.25": int((preds > 0.25).sum()),
        "scores_gt_0.5":  int((preds > 0.5).sum()),
        "scores_gt_1.0":  int((preds > 1.0).sum()),
        "coords_sample":  preds[:4, :5].tolist(),
    }

@app.post("/infer")
async def infer(req: InferRequest):
    """Принимает PNG/JPG как base64, возвращает count и боксы."""
    img_bytes = base64.b64decode(req.image_base64)
    pil_img   = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    # Конвертация как в ноутбуке: RGB → BGR → resize → RGB → NCHW float32
    img_bgr   = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
    resized   = cv2.resize(img_bgr, (INPUT_SIZE, INPUT_SIZE))
    rgb       = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
    nchw      = rgb.transpose(2, 0, 1).astype(np.float32)[np.newaxis] / 255.0

    async with _sem:
        loop   = asyncio.get_event_loop()
        output = await loop.run_in_executor(None, _run_single, nchw)

    dets  = run_nms(output)
    boxes = [{"x1": d["x1"], "y1": d["y1"], "x2": d["x2"], "y2": d["y2"],
               "conf": d["conf"], "class": CLASS_NAMES.get(d["cls_id"], "?")}
             for d in dets]
    return {"eosinophil_count": len(dets), "boxes": boxes}

@app.post("/infer_raw")
async def infer_raw(req: TensorRequest):
    """Одиночный патч тензором float32 — для тестов."""
    raw  = base64.b64decode(req.tensor_base64)
    nchw = np.frombuffer(raw, dtype=np.float32).reshape(1, 3, INPUT_SIZE, INPUT_SIZE).copy()

    async with _sem:
        loop   = asyncio.get_event_loop()
        output = await loop.run_in_executor(None, _run_single, nchw)

    meta   = PatchItem(patch_id="single", tensor_base64=req.tensor_base64,
                       patch_wsi_size=req.patch_wsi_size, overlap_px=req.overlap_px,
                       edge_left=req.edge_left, edge_top=req.edge_top,
                       edge_right=req.edge_right, edge_bottom=req.edge_bottom)
    dets   = run_nms(output)
    return apply_overlap_filter(dets, meta)

@app.post("/infer_batch")
async def infer_batch(req: BatchTensorRequest):
    """Батч патчей — один GPU-прогон для N патчей."""
    async with _sem:
        loop    = asyncio.get_event_loop()
        results = await loop.run_in_executor(None, _run_batch, req.patches)
    return {"results": results}