from fastapi import FastAPI
from pydantic import BaseModel
from PIL import Image
import base64, io, numpy as np, os, shutil
import logging
import onnxruntime as ort


logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(levelname)s  %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI()

# ── Путь к модели ─────────────────────────────────────────────────────────────
# ↓↓↓ ПОМЕНЯЙ НА СВОЙ ПУТЬ К best.onnx ↓↓↓
ONNX_PATH = os.environ.get("MODEL_PATH", "./triton-models/eosin_yolo/1/best.onnx")

providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
session = ort.InferenceSession(ONNX_PATH, providers=providers)

INPUT_NAME  = session.get_inputs()[0].name   # "images"
OUTPUT_NAME = session.get_outputs()[0].name  # "output0"

# Имена классов из твоей модели
CLASS_NAMES = {0: "eos", 1: "eosg"}

logger.info(f"Model loaded: {ONNX_PATH}")
logger.info(f"Providers active: {session.get_providers()}")
logger.info(f"Input:  {INPUT_NAME} {session.get_inputs()[0].shape}")
logger.info(f"Output: {OUTPUT_NAME} {session.get_outputs()[0].shape}")


@app.on_event("startup")
def cleanup_runs():
    shutil.rmtree("runs", ignore_errors=True)
    logger.info("Cleared YOLO runs cache")


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


@app.get("/health")
def health():
    return {
        "status":    "ok",
        "model":     ONNX_PATH,
        "providers": session.get_providers(),
    }


@app.post("/infer")
def infer(req: InferRequest):
    """Инференс по base64-изображению (PNG/JPEG)."""
    img_bytes = base64.b64decode(req.image_base64)
    img = Image.open(io.BytesIO(img_bytes)).convert("RGB").resize((448, 448))
    tensor = np.array(img, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0

    outputs = session.run([OUTPUT_NAME], {INPUT_NAME: tensor})
    boxes, confs, class_ids = _parse(outputs[0], conf_thr=0.15)
    keep = _nms(boxes, confs)

    count = len(keep)
    result_boxes = []
    for i in keep:
        x1, y1, x2, y2 = _cxcywh_to_xyxy(boxes[i])
        result_boxes.append({"x1": float(x1), "y1": float(y1),
                              "x2": float(x2), "y2": float(y2),
                              "conf": float(confs[i])})
    return {"eosinophil_count": count, "boxes": result_boxes}


@app.post("/infer_raw")
def infer_raw(req: TensorRequest):
    """Инференс по float32 NCHW тензору из Java."""
    raw    = base64.b64decode(req.tensor_base64)
    tensor = np.frombuffer(raw, dtype=np.float32).reshape(1, 3, 448, 448)

    outputs = session.run([OUTPUT_NAME], {INPUT_NAME: tensor})
    boxes, confs, class_ids = _parse(outputs[0], conf_thr=0.15)
    keep = _nms(boxes, confs, iou_thr=0.5)

    half_ov = req.overlap_px / 2
    lo_x = half_ov if not req.edge_left   else 0.0
    lo_y = half_ov if not req.edge_top    else 0.0
    hi_x = (req.patch_wsi_size - half_ov) if not req.edge_right  else float(req.patch_wsi_size)
    hi_y = (req.patch_wsi_size - half_ov) if not req.edge_bottom else float(req.patch_wsi_size)

    eos_count = eosg_count = valid_eos = valid_eosg = 0

    for i in keep:
        name = CLASS_NAMES.get(int(class_ids[i]), "unknown")
        x1, y1, x2, y2 = _cxcywh_to_xyxy(boxes[i])
        cx = (x1 + x2) / 2
        cy = (y1 + y2) / 2

        if name == "eos":    eos_count  += 1
        elif name == "eosg": eosg_count += 1

        if lo_x <= cx <= hi_x and lo_y <= cy <= hi_y:
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


# ── Вспомогательные функции ───────────────────────────────────────────────────

def _parse(output0: np.ndarray, conf_thr: float):
    """
    output0: (1, 38, 4116)
    Колонки: 0-3 = cx,cy,w,h  |  4 = conf_eos  |  5 = conf_eosg  |  6-37 = маски
    """
    preds = output0[0].T          # (4116, 38)
    class_confs = preds[:, 4:6]   # (4116, 2)
    max_conf    = class_confs.max(axis=1)
    class_ids   = class_confs.argmax(axis=1)

    mask    = max_conf >= conf_thr
    return preds[mask, :4], max_conf[mask], class_ids[mask]


def _nms(boxes: np.ndarray, scores: np.ndarray, iou_thr: float = 0.45):
    """NMS → список индексов оставленных детекций."""
    if len(boxes) == 0:
        return []

    x1 = boxes[:, 0] - boxes[:, 2] / 2
    y1 = boxes[:, 1] - boxes[:, 3] / 2
    x2 = boxes[:, 0] + boxes[:, 2] / 2
    y2 = boxes[:, 1] + boxes[:, 3] / 2
    areas  = (x2 - x1) * (y2 - y1)
    order  = scores.argsort()[::-1]
    keep   = []

    while order.size > 0:
        i = order[0]
        keep.append(i)
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        inter = np.maximum(0, xx2 - xx1) * np.maximum(0, yy2 - yy1)
        iou   = inter / (areas[i] + areas[order[1:]] - inter + 1e-6)
        order = order[1:][iou < iou_thr]

    return keep


def _cxcywh_to_xyxy(box):
    cx, cy, w, h = box
    return cx - w/2, cy - h/2, cx + w/2, cy + h/2