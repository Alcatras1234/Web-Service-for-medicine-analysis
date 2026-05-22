"""
Inference-service для eosinophil_seg на удалённом Triton.

Шлёт FP32 CHW [1,3,448,448] по gRPC → модель eosinophil_seg.
Получает output0 (1,38,4116) и output1 (1,32,112,112) — делает NMS в Python.
"""

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
import tritonclient.grpc as triton_grpc
from tritonclient.utils import np_to_triton_dtype
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s  %(levelname)s  %(message)s")
logger = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────
CONF_THRESH  = float(os.environ.get("CONF_THRESH",  "0.25"))
IOU_THRESH   = float(os.environ.get("IOU_THRESH",   "0.45"))
INPUT_SIZE   = int(os.environ.get("INPUT_SIZE",     "448"))
NUM_CLASSES  = int(os.environ.get("NUM_CLASSES",    "2"))
WHITE_THRESH = float(os.environ.get("WHITE_THRESH", "240"))
MASK_DIM     = int(os.environ.get("MASK_DIM",       "32"))

# ── Triton gRPC ───────────────────────────────────────────────────────────────
# По умолчанию обращаемся к удалённому Triton на сервере (188.126.62.18:18001).
TRITON_URL        = os.environ.get("TRITON_URL",        "188.126.62.18:18001")
TRITON_MODEL_NAME = os.environ.get("TRITON_MODEL_NAME", "eosinophil_seg")
TRITON_INPUT_NAME = os.environ.get("TRITON_INPUT_NAME", "images")
TRITON_TIMEOUT_S  = int(os.environ.get("TRITON_TIMEOUT_S", "60"))

logger.info(f"Triton: url={TRITON_URL} model={TRITON_MODEL_NAME} input={TRITON_INPUT_NAME}")
logger.info(f"Params: input={INPUT_SIZE} nc={NUM_CLASSES} conf={CONF_THRESH} iou={IOU_THRESH}")

# Keep-alive — gRPC канал не засыпает между батчами (remote, поэтому жирные таймауты).
_TRITON_CHANNEL_ARGS = [
    ("grpc.keepalive_time_ms",                          30000),
    ("grpc.keepalive_timeout_ms",                        5000),
    ("grpc.keepalive_permit_without_calls",                 1),
    ("grpc.http2.max_pings_without_data",                   0),
    ("grpc.http2.min_time_between_pings_ms",            10000),
    ("grpc.http2.min_ping_interval_without_data_ms",     5000),
    ("grpc.max_receive_message_length",            64 * 1024 * 1024),
    ("grpc.max_send_message_length",               64 * 1024 * 1024),
]

triton_client = triton_grpc.InferenceServerClient(
    url=TRITON_URL, verbose=False, channel_args=_TRITON_CHANNEL_ARGS,
)

CLASS_NAMES = {0: "eos", 1: "eosg"}
MODEL_VERSION = os.environ.get(
    "MODEL_VERSION",
    f"triton/{TRITON_MODEL_NAME}@{TRITON_URL}"
)

GPU_EXECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="triton")
_session_lock = asyncio.Lock()
_COORD_LOG_DONE = False


def _maybe_recreate_session(err: Exception):
    """При gRPC-разрыве пересоздаём клиента с теми же keep-alive настройками."""
    global triton_client
    msg = str(err)
    if "UNAVAILABLE" in msg or "channel" in msg.lower() or "deadline" in msg.lower():
        logger.error(f"Triton gRPC error: {msg}. Recreating client...")
        try:
            triton_client = triton_grpc.InferenceServerClient(
                url=TRITON_URL, verbose=False, channel_args=_TRITON_CHANNEL_ARGS,
            )
            logger.info("Triton client recreated")
        except Exception as e:
            logger.exception(f"Failed to recreate Triton client: {e}")


# ── Preprocess ────────────────────────────────────────────────────────────────
def preprocess(hwc_uint8: np.ndarray) -> np.ndarray:
    """RGB HWC uint8 → NCHW float32 [0,1]. YOLO Ultralytics не использует ImageNet norm."""
    if hwc_uint8.shape[:2] != (INPUT_SIZE, INPUT_SIZE):
        hwc_uint8 = cv2.resize(hwc_uint8, (INPUT_SIZE, INPUT_SIZE))
    f = hwc_uint8.astype(np.float32) * (1.0 / 255.0)
    return np.ascontiguousarray(f.transpose(2, 0, 1)[np.newaxis])


# ── NMS + опциональный декод масок ────────────────────────────────────────────
def _sigmoid(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(x, -88, 88)))


def _decode_masks(mask_coefs: np.ndarray, protos: np.ndarray,
                  boxes_xyxy: np.ndarray) -> List[np.ndarray]:
    """mask_coefs:(N,32), protos:(32,ph,pw), boxes:(N,4) в 448×448 → бинарные маски."""
    ph, pw = protos.shape[1], protos.shape[2]
    proto_flat = protos.reshape(MASK_DIM, -1)
    raw = mask_coefs @ proto_flat
    masks = _sigmoid(raw).reshape(-1, ph, pw)
    out = []
    for i in range(masks.shape[0]):
        m = cv2.resize(masks[i], (INPUT_SIZE, INPUT_SIZE), interpolation=cv2.INTER_LINEAR)
        x1, y1, x2, y2 = boxes_xyxy[i].astype(int).clip(0, INPUT_SIZE)
        cropped = np.zeros_like(m, dtype=np.uint8)
        cropped[y1:y2, x1:x2] = (m[y1:y2, x1:x2] > 0.5).astype(np.uint8)
        out.append(cropped)
    return out


def run_nms(triton_out: dict) -> list:
    """
    triton_out: {'output0': (1, 38, 4116), 'output1': (1, 32, 112, 112)}
    Возвращает список детекций с полями cls_id, cx/cy, x1/y1/x2/y2, conf, cc.
    Координаты в 448×448 (== patch_wsi_size).
    """
    out0 = triton_out["output0"][0]                                    # (38, 4116)
    preds = out0.T                                                      # (4116, 38)

    boxes_xywh   = preds[:, 0:4]
    class_scores = preds[:, 4:4 + NUM_CLASSES]                          # sigmoid уже в ONNX-экспорте
    mask_coefs   = preds[:, 4 + NUM_CLASSES:4 + NUM_CLASSES + MASK_DIM]

    class_ids = np.argmax(class_scores, axis=1)
    confs     = np.max(class_scores, axis=1)

    keep0 = confs > CONF_THRESH
    if not np.any(keep0):
        return []

    boxes_xywh = boxes_xywh[keep0]
    confs      = confs[keep0]
    class_ids  = class_ids[keep0]
    mask_coefs = mask_coefs[keep0]

    cx, cy, w, h = boxes_xywh[:, 0], boxes_xywh[:, 1], boxes_xywh[:, 2], boxes_xywh[:, 3]
    x1 = cx - w / 2; y1 = cy - h / 2; x2 = cx + w / 2; y2 = cy + h / 2
    boxes_xyxy = np.stack([x1, y1, x2, y2], axis=1)

    boxes_for_nms = np.stack([x1, y1, w, h], axis=1).astype(np.float32).tolist()
    keep_idx = cv2.dnn.NMSBoxes(boxes_for_nms, confs.astype(np.float32).tolist(),
                                CONF_THRESH, IOU_THRESH)
    if len(keep_idx) == 0:
        return []
    keep_idx = np.array(keep_idx).flatten()

    use_cc = os.environ.get("USE_CC_COUNT", "false").lower() == "true"
    cc_counts = None
    if use_cc:
        protos = triton_out["output1"][0]
        cell_masks = _decode_masks(mask_coefs[keep_idx], protos, boxes_xyxy[keep_idx])
        cc_counts = []
        for m in cell_masks:
            n_components, _ = cv2.connectedComponents(m, connectivity=8)
            cc_counts.append(max(1, n_components - 1))

    global _COORD_LOG_DONE
    if not _COORD_LOG_DONE:
        _COORD_LOG_DONE = True
        logger.info(
            f"FIRST DETECTION: kept={len(keep_idx)} of {len(confs)}, "
            f"conf_max={float(confs.max()):.2f}, "
            f"x_max={float(x2.max()):.1f}, y_max={float(y2.max()):.1f}"
        )

    out = []
    for j, i in enumerate(keep_idx):
        out.append({
            "cls_id": int(class_ids[i]),
            "cx":   float((x1[i] + x2[i]) / 2),
            "cy":   float((y1[i] + y2[i]) / 2),
            "x1":   float(x1[i]), "y1": float(y1[i]),
            "x2":   float(x2[i]), "y2": float(y2[i]),
            "conf": float(confs[i]),
            "cc":   int(cc_counts[j]) if cc_counts is not None else 1,
        })
    return out


def apply_overlap_filter(dets: list, meta) -> dict:
    """§3.4: inner-zone расширяется до края на внешних границах WSI."""
    use_cc = os.environ.get("USE_CC_COUNT", "false").lower() == "true"

    half_ov = meta.overlap_px / 2
    lo_x = 0.0 if meta.edge_left  else half_ov
    lo_y = 0.0 if meta.edge_top   else half_ov
    hi_x = meta.patch_wsi_size if meta.edge_right  else meta.patch_wsi_size - half_ov
    hi_y = meta.patch_wsi_size if meta.edge_bottom else meta.patch_wsi_size - half_ov

    eos_count = eosg_count = valid_eos = valid_eosg = 0
    cc_estimate_eos = cc_estimate_eosg = 0
    valid_dets = []

    for d in dets:
        name = CLASS_NAMES.get(d["cls_id"], "unknown")
        cc_cells = int(d.get("cc", 1))
        cells_for_count = cc_cells if use_cc else 1

        if name == "eos":
            eos_count       += cells_for_count
            cc_estimate_eos += cc_cells
        elif name == "eosg":
            eosg_count       += cells_for_count
            cc_estimate_eosg += cc_cells

        if lo_x <= d["cx"] <= hi_x and lo_y <= d["cy"] <= hi_y:
            if name == "eos":    valid_eos  += cells_for_count
            elif name == "eosg": valid_eosg += cells_for_count
            valid_dets.append({
                "cls":  name,
                "cx":   d["cx"], "cy": d["cy"],
                "x1":   d["x1"], "y1": d["y1"],
                "x2":   d["x2"], "y2": d["y2"],
                "conf": d["conf"],
                "cells": cells_for_count,
                "cc":    cc_cells,
            })

    return {
        "patch_id":         getattr(meta, "patch_id", "single"),
        "total_count":      eos_count + eosg_count,
        "valid_count":      valid_eos + valid_eosg,
        "valid_eos":        valid_eos,
        "valid_eosg":       valid_eosg,
        "cc_estimate_eos":  cc_estimate_eos,
        "cc_estimate_eosg": cc_estimate_eosg,
        "detections":      valid_dets,
    }


# ── Triton gRPC инференс ──────────────────────────────────────────────────────
def _run_blocking(chw_fp32: np.ndarray) -> dict:
    """chw_fp32: (1,3,448,448) float32. Возвращает {output0, output1} от Triton."""
    inp = triton_grpc.InferInput(TRITON_INPUT_NAME, list(chw_fp32.shape),
                                  np_to_triton_dtype(chw_fp32.dtype))
    inp.set_data_from_numpy(chw_fp32)

    requested = [
        triton_grpc.InferRequestedOutput("output0"),
        triton_grpc.InferRequestedOutput("output1"),
    ]

    response = triton_client.infer(
        model_name=TRITON_MODEL_NAME,
        inputs=[inp],
        outputs=requested,
        client_timeout=TRITON_TIMEOUT_S,
    )
    return {
        "output0": response.as_numpy("output0"),
        "output1": response.as_numpy("output1"),
    }


async def run_session(hwc_uint8: np.ndarray) -> dict:
    """HWC uint8 → preprocess → Triton → dict {output0, output1}."""
    chw = preprocess(hwc_uint8)
    loop = asyncio.get_event_loop()
    try:
        return await loop.run_in_executor(GPU_EXECUTOR, _run_blocking, chw)
    except Exception as e:
        async with _session_lock:
            _maybe_recreate_session(e)
        raise


# ── FastAPI ───────────────────────────────────────────────────────────────────
app = FastAPI()


@app.on_event("startup")
def cleanup_runs():
    shutil.rmtree("runs", ignore_errors=True)
    logger.info("Cleared YOLO runs cache")


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


@app.get("/health")
def health():
    try:
        triton_live = triton_client.is_server_live()
        model_ready = triton_client.is_model_ready(TRITON_MODEL_NAME)
    except Exception as e:
        triton_live, model_ready = False, False
        logger.warning(f"health check failed: {e}")
    return {
        "status":        "ok" if (triton_live and model_ready) else "degraded",
        "triton_url":    TRITON_URL,
        "triton_live":   triton_live,
        "model_name":    TRITON_MODEL_NAME,
        "model_ready":   model_ready,
        "model_version": MODEL_VERSION,
        "input_size":    INPUT_SIZE,
        "num_classes":   NUM_CLASSES,
        "conf_thresh":   CONF_THRESH,
        "iou_thresh":    IOU_THRESH,
    }


@app.post("/infer")
async def infer(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    hwc = np.array(pil_img)
    try:
        out = await run_session(hwc)
    except Exception as e:
        raise HTTPException(500, f"Inference failed: {e}")
    dets = run_nms(out)
    total_cells = sum(int(d.get("cc", 1)) for d in dets)
    return {
        "eosinophil_count": total_cells,
        "boxes": [{"x1": d["x1"], "y1": d["y1"],
                   "x2": d["x2"], "y2": d["y2"],
                   "conf": d["conf"], "cells": int(d.get("cc", 1))} for d in dets],
    }


@app.post("/infer_raw")
async def infer_raw(req: TensorRequest):
    raw = base64.b64decode(req.tensor_base64)
    hwc = np.frombuffer(raw, dtype=np.uint8).reshape(INPUT_SIZE, INPUT_SIZE, 3).copy()
    if float(hwc.mean()) > WHITE_THRESH:
        return apply_overlap_filter([], req)
    try:
        out = await run_session(hwc)
    except Exception as e:
        raise HTTPException(500, f"Inference failed: {e}")
    dets = run_nms(out)
    return apply_overlap_filter(dets, req)


@app.post("/infer_batch")
async def infer_batch(req: BatchTensorRequest):
    results = []
    resp = {"model_version": MODEL_VERSION, "results": results}
    for patch in req.patches:
        raw = base64.b64decode(patch.tensor_base64)
        hwc = np.frombuffer(raw, dtype=np.uint8).reshape(INPUT_SIZE, INPUT_SIZE, 3).copy()
        if float(hwc.mean()) > WHITE_THRESH:
            results.append({"patch_id": patch.patch_id,
                            "total_count": 0, "valid_count": 0,
                            "valid_eos": 0, "valid_eosg": 0,
                            "detections": []})
            continue
        try:
            out = await run_session(hwc)
        except Exception as e:
            logger.error(f"patch {patch.patch_id} failed: {e}")
            results.append({"patch_id": patch.patch_id,
                            "total_count": 0, "valid_count": 0,
                            "valid_eos": 0, "valid_eosg": 0,
                            "detections": []})
            continue
        dets = run_nms(out)
        results.append(apply_overlap_filter(dets, patch))
    return resp


@app.post("/debug_raw")
async def debug_raw(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    hwc = np.array(pil_img)
    out = await run_session(hwc)
    dets = run_nms(out)
    return {
        "model":          TRITON_MODEL_NAME,
        "num_detections": len(dets),
        "conf_max":       max((d["conf"] for d in dets), default=None),
        "classes_unique": sorted({d["cls_id"] for d in dets}),
    }
