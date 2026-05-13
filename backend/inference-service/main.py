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
CONF_THRESH = float(os.environ.get("CONF_THRESH", "0.25"))
IOU_THRESH  = float(os.environ.get("IOU_THRESH",  "0.45"))
INPUT_SIZE  = int(os.environ.get("INPUT_SIZE",    "448"))
NUM_CLASSES = int(os.environ.get("NUM_CLASSES",   "2"))
WHITE_THRESH = float(os.environ.get("WHITE_THRESH", "240"))
# Если Triton pipeline внутри ресайзит к другому размеру (например 640) и
# возвращает координаты в его пиксельной системе, мы получаем смещение.
# COORD_SCALE_FACTOR = INPUT_SIZE / actual_model_input_size. Например 448/640 = 0.7.
# По умолчанию 1.0 — если bbox видны со сдвигом/масштабом, подкрути.
COORD_SCALE_FACTOR = float(os.environ.get("COORD_SCALE_FACTOR", "1.0"))

# ── Triton gRPC ───────────────────────────────────────────────────────────────
# host:port, например "188.126.62.18:18001"  (без http://)
TRITON_URL        = os.environ.get("TRITON_URL", "188.126.62.18:18001")
TRITON_MODEL_NAME = os.environ.get("TRITON_MODEL_NAME", "eosin_yolo")
TRITON_TIMEOUT_S  = int(os.environ.get("TRITON_TIMEOUT_S", "60"))

logger.info(f"Connecting to Triton {TRITON_URL} (model={TRITON_MODEL_NAME})")

# Keep-alive параметры — чтобы gRPC канал не засыпал между батчами.
# RTT до Triton (188.126.62.18) ~150ms; cold reconnect = ~750ms потерь на батч,
# а у нас бывают паузы 5-15 сек между батчами (пока качаются патчи) → канал
# успевает закрыться. Эти настройки шлют пустой ping каждые 10 сек, держа TCP живым.
_TRITON_CHANNEL_ARGS = [
    ("grpc.keepalive_time_ms",                          10000),  # ping каждые 10 сек
    ("grpc.keepalive_timeout_ms",                        5000),  # ждём ответ 5 сек
    ("grpc.keepalive_permit_without_calls",                 1),  # пинговать даже без активных запросов
    ("grpc.http2.max_pings_without_data",                   0),  # без лимита на ping'и без данных
    ("grpc.http2.min_time_between_pings_ms",            10000),
    ("grpc.http2.min_ping_interval_without_data_ms",     5000),
    # Увеличиваем max receive size до 64 МБ — Triton может вернуть здоровенные тензоры
    ("grpc.max_receive_message_length",            64 * 1024 * 1024),
    ("grpc.max_send_message_length",               64 * 1024 * 1024),
]

triton_client = triton_grpc.InferenceServerClient(
    url=TRITON_URL,
    verbose=False,
    channel_args=_TRITON_CHANNEL_ARGS,
)

# Дефолты на случай если Triton сейчас недоступен — приложение должно стартовать
# и при первом запросе попробует подключиться. Без этого контейнер падает в loop.
input_name        = os.environ.get("TRITON_INPUT_NAME",  "image_input")
det_output_name   = os.environ.get("TRITON_OUTPUT_NAME", "output0")
proto_output_name = os.environ.get("TRITON_PROTO_NAME",  "output1")
input_shape       = [-1, 3, INPUT_SIZE, INPUT_SIZE]
out_shape         = [-1, 4 + NUM_CLASSES + 32, 4116]
all_outputs       = [det_output_name, proto_output_name]
IS_SEG_MODEL      = True   # по умолчанию ожидаем seg-модель

try:
    if not triton_client.is_server_live():
        logger.warning(f"Triton at {TRITON_URL} not live yet — будет повтор при первом запросе")
    elif not triton_client.is_model_ready(TRITON_MODEL_NAME):
        logger.warning(f"Triton model '{TRITON_MODEL_NAME}' not ready yet — будет повтор")
    else:
        _meta = triton_client.get_model_metadata(TRITON_MODEL_NAME)
        input_name      = _meta.inputs[0].name
        input_shape     = list(_meta.inputs[0].shape)
        det_output_name = _meta.outputs[0].name
        out_shape       = list(_meta.outputs[0].shape)
        all_outputs     = [o.name for o in _meta.outputs]
        logger.info(f"Triton input  {input_name} shape={input_shape}")
        logger.info(f"Triton outputs: {all_outputs} (head shape={out_shape})")

        expected_detect = 4 + NUM_CLASSES
        expected_seg    = 4 + NUM_CLASSES + 32
        ch = out_shape[1] if isinstance(out_shape[1], int) and out_shape[1] > 0 else None

        IS_SEG_MODEL = (ch == expected_seg) and (len(_meta.outputs) >= 2)
        if IS_SEG_MODEL:
            proto_output_name = _meta.outputs[1].name
            logger.info(f"✓ SEGMENTATION MODEL. Proto output '{proto_output_name}' "
                        f"shape={list(_meta.outputs[1].shape)}.")
        elif ch == expected_detect:
            proto_output_name = None
            logger.info("✓ DETECTION-only model. Подсчёт по bbox (после NMS).")
        else:
            logger.warning(
                f"!!! Triton output channels = {ch}, ожидали {expected_detect} (detect) "
                f"или {expected_seg} (seg) для NUM_CLASSES={NUM_CLASSES}."
            )
except Exception as e:
    logger.warning(f"Triton metadata fetch failed: {e}. "
                   f"Старт продолжается с дефолтами; первый запрос упадёт если Triton не оживёт.")

CLASS_NAMES = {0: "eos", 1: "eosg"}

# Версия модели — берём из Triton config, либо из env
MODEL_VERSION = os.environ.get(
    "MODEL_VERSION",
    f"triton/{TRITON_MODEL_NAME}@{TRITON_URL}"
)

# Пул потоков на сетевые вызовы Triton — параллельные запросы можно гонять
# (Triton сам очередит и батчит). max_workers=4 — типичная разумная оценка.
GPU_EXECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="triton")
_session_lock = asyncio.Lock()


def _maybe_recreate_session(err: Exception):
    """При gRPC-разрыве пересоздаём клиента с теми же keep-alive настройками."""
    global triton_client
    msg = str(err)
    if "UNAVAILABLE" in msg or "channel" in msg.lower() or "deadline" in msg.lower():
        logger.error(f"Triton gRPC error: {msg}. Recreating client...")
        try:
            triton_client = triton_grpc.InferenceServerClient(
                url=TRITON_URL,
                verbose=False,
                channel_args=_TRITON_CHANNEL_ARGS,
            )
            logger.info("Triton client recreated with keep-alive")
        except Exception as e:
            logger.exception(f"Failed to recreate Triton client: {e}")


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
    if COORD_SCALE_FACTOR != 1.0:
        coords = coords * COORD_SCALE_FACTOR  # ресайз если pipeline отдаёт в др. простр-ве
    scores = preds[4:4 + NUM_CLASSES]    # (nc, anchors)

    if scores.size == 0:
        return []
    if scores.max() > 1.0:
        scores = _sigmoid(scores)

    cls_ids    = scores.argmax(axis=0)
    cls_scores = scores.max(axis=0)

    # Debug: один раз залогируем диапазон координат из pipeline,
    # чтобы понять в каком пространстве (448? 640?) выдаются bbox'ы.
    global _COORD_LOG_DONE
    try:
        _COORD_LOG_DONE
    except NameError:
        _COORD_LOG_DONE = False
    if not _COORD_LOG_DONE and coords.size > 0 and cls_scores.max() > CONF_THRESH:
        _COORD_LOG_DONE = True
        valid_mask = cls_scores > CONF_THRESH
        valid_coords = coords[valid_mask]
        if len(valid_coords) > 0:
            logger.info(
                f"FIRST DETECTION (DEBUG): coords ranges  x_min={valid_coords[:,0].min():.1f} "
                f"x_max={valid_coords[:,0].max():.1f}  w_max={valid_coords[:,2].max():.1f}  "
                f"(INPUT_SIZE={INPUT_SIZE}, scale={COORD_SCALE_FACTOR}). "
                f"Если x_max сильно больше {INPUT_SIZE} → выстави COORD_SCALE_FACTOR={INPUT_SIZE}/<actual_max>"
            )

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
    Подсчёт клеток на патче.

    §3.1+§3.2: классы — eos (intact, с ядром) и eosg (granulated, без ядра).
    §3.4: на внешних границах WSI inner-zone расширяется до края — нет dead zone.

    Правило подсчёта (клиническое, по запросу патолога):
      • Каждый bbox = ОДНА клетка, независимо от того, что показала CC-маска.
        Это совпадает с тем как считает патолог под микроскопом: «один объект
        с ядром = +1». CC от seg-маски часто завышает счёт на артефактах маски.
      • Поле `cells` (число CC-компонент) сохраняется в JSON детекции как
        СПРАВОЧНОЕ — для аудита и возможной ретроспективы.
      • intact (eos) и granulated (eosg) учитываются раздельно. По intact
        ставится диагноз EoE (≥15/HPF), granulated — справочный признак
        активной дегрануляции.

    Поведение управляется env-переменной USE_CC_COUNT (по умолчанию false).
    """
    use_cc = os.environ.get("USE_CC_COUNT", "false").lower() == "true"

    half_ov = meta.overlap_px / 2
    lo_x = 0.0 if meta.edge_left  else half_ov
    lo_y = 0.0 if meta.edge_top   else half_ov
    hi_x = meta.patch_wsi_size if meta.edge_right  else meta.patch_wsi_size - half_ov
    hi_y = meta.patch_wsi_size if meta.edge_bottom else meta.patch_wsi_size - half_ov

    eos_count = eosg_count = valid_eos = valid_eosg = 0
    cc_estimate_eos = cc_estimate_eosg = 0   # справочно: что бы дала CC-логика
    valid_dets = []

    for d in dets:
        name = CLASS_NAMES.get(d["cls_id"], "unknown")
        cc_cells = int(d.get("cc", 1))           # справочно — что показала маска
        cells_for_count = cc_cells if use_cc else 1   # по умолчанию: 1 клетка = 1 bbox

        if name == "eos":
            eos_count       += cells_for_count
            cc_estimate_eos += cc_cells
        elif name == "eosg":
            eosg_count       += cells_for_count
            cc_estimate_eosg += cc_cells

        if lo_x <= d["cx"] <= hi_x and lo_y <= d["cy"] <= hi_y:
            if name == "eos":   valid_eos  += cells_for_count
            elif name == "eosg": valid_eosg += cells_for_count
            valid_dets.append({
                "cls": name,
                "cx": d["cx"], "cy": d["cy"],
                "x1": d["x1"], "y1": d["y1"],
                "x2": d["x2"], "y2": d["y2"],
                "conf": d["conf"],
                "cells": cells_for_count,         # сколько учли в счёт (1 в дефолте)
                "cc": cc_cells,                   # справочно: число CC-компонент
            })

    return {
        "patch_id":    getattr(meta, "patch_id", "single"),
        "total_count": eos_count + eosg_count,
        "valid_count": valid_eos + valid_eosg,
        "valid_eos":   valid_eos,                 # ← диагностический intact
        "valid_eosg":  valid_eosg,                # ← granulated, отдельно
        "cc_estimate_eos":  cc_estimate_eos,      # справочно: что было бы с CC
        "cc_estimate_eosg": cc_estimate_eosg,
        "detections":  valid_dets,
    }


# ── Triton gRPC инференс ──────────────────────────────────────────────────────
def _run_blocking(hwc_uint8: np.ndarray):
    """
    Шлёт изображение в Triton (eosinophil_pipeline) и возвращает (det, proto).

    ВАЖНО: pipeline на Triton ВКЛЮЧАЕТ препроцессинг (resize/normalize/transpose),
    поэтому ему нужны СЫРЫЕ uint8 RGB пиксели (HWC), а не наш float32 NCHW.
    Имя входа — image_input. Если pipeline ожидает другую раскладку (NCHW vs HWC) —
    надо смотреть его `config.pbtxt`.
    """
    # HWC uint8 (H, W, 3) — обычно pipeline через DALI/Python backend сам ресайзит/нормализует
    # Если pipeline хочет NCHW uint8 — выставь env TRITON_INPUT_LAYOUT=NCHW
    if os.environ.get("TRITON_INPUT_LAYOUT", "HWC").upper() == "NCHW":
        data = np.ascontiguousarray(hwc_uint8.transpose(2, 0, 1)[np.newaxis], dtype=np.uint8)  # (1,3,H,W)
    else:
        data = np.ascontiguousarray(hwc_uint8[np.newaxis], dtype=np.uint8)                     # (1,H,W,3)

    inp = triton_grpc.InferInput(input_name, list(data.shape), np_to_triton_dtype(data.dtype))
    inp.set_data_from_numpy(data)

    requested = [triton_grpc.InferRequestedOutput(det_output_name)]
    if IS_SEG_MODEL and proto_output_name:
        requested.append(triton_grpc.InferRequestedOutput(proto_output_name))

    response = triton_client.infer(
        model_name=TRITON_MODEL_NAME,
        inputs=[inp],
        outputs=requested,
        client_timeout=TRITON_TIMEOUT_S,
    )
    det = response.as_numpy(det_output_name)
    proto = response.as_numpy(proto_output_name) if (IS_SEG_MODEL and proto_output_name) else None
    return det, proto


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
    try:
        triton_live  = triton_client.is_server_live()
        model_ready  = triton_client.is_model_ready(TRITON_MODEL_NAME)
    except Exception as e:
        triton_live, model_ready = False, False
        logger.warning(f"health check failed: {e}")
    return {"status": "ok" if (triton_live and model_ready) else "degraded",
            "triton_url":   TRITON_URL,
            "triton_live":  triton_live,
            "model_name":   TRITON_MODEL_NAME,
            "model_ready":  model_ready,
            "is_seg_model": IS_SEG_MODEL,
            "model_version": MODEL_VERSION,
            "input_shape":   input_shape,
            "output_shape":  out_shape,
            "num_outputs":   len(all_outputs),
            "conf_thresh":   CONF_THRESH,
            "num_classes":   NUM_CLASSES}


@app.post("/infer")
async def infer(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    pil_img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    img_rgb = np.array(pil_img)
    # Triton pipeline сам ресайзит — но мы всё равно даём 448×448 для консистентности
    resized = cv2.resize(img_rgb, (INPUT_SIZE, INPUT_SIZE)).astype(np.uint8)
    try:
        det, proto = await run_session(resized)
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

    try:
        det, proto = await run_session(hwc)        # сырой HWC uint8 — Triton сам нормализует
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
        try:
            det, proto = await run_session(hwc)    # raw HWC uint8 → Triton pipeline
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
    resized = cv2.resize(img_rgb, (INPUT_SIZE, INPUT_SIZE)).astype(np.uint8)
    det, proto = await run_session(resized)
    scores = det[0][4:4 + NUM_CLASSES, :]
    return {
        "is_seg_model":   IS_SEG_MODEL,
        "det_shape":      list(det.shape),
        "proto_shape":    list(proto.shape) if proto is not None else None,
        "num_outputs":    len(all_outputs),
        "scores_min":     float(scores.min()),
        "scores_max":     float(scores.max()),
        "scores_gt_0.25": int((scores.max(axis=0) > 0.25).sum()),
        "scores_gt_0.5":  int((scores.max(axis=0) > 0.5).sum()),
    }