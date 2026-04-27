from fastapi import FastAPI
from pydantic import BaseModel
from ultralytics import YOLO
from PIL import Image
import base64, io, torch, numpy as np
import logging

# ── Фикс "Conv has no attribute bn" ─────────────────────────────────────────
import ultralytics.nn.tasks as _tasks

_orig_fuse = _tasks.DetectionModel.fuse

def _safe_fuse(self, verbose=True):
    try:
        return _orig_fuse(self, verbose=verbose)
    except AttributeError:
        return self

_tasks.DetectionModel.fuse = _safe_fuse
# ────────────────────────────────────────────────────────────────────────────

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI()

MODEL_PATH = "./triton-models/eosin_yolo/1/best.pt"
DEVICE     = 0 if torch.cuda.is_available() else "cpu"

logger.info(f"Loading model from {MODEL_PATH} on device: {DEVICE}")
model = YOLO(MODEL_PATH)
logger.info("Model loaded OK")


class InferRequest(BaseModel):
    image_base64: str


class TensorRequest(BaseModel):
    tensor_base64:  str
    patch_wsi_size: int  = 448   # размер патча в WSI-пикселях
    overlap_px:     int  = 24    # нахлёст = patch_wsi_size - stride
    edge_left:      bool = False
    edge_top:       bool = False
    edge_right:     bool = False
    edge_bottom:    bool = False


@app.get("/health")
def health():
    return {"status": "ok", "cuda": torch.cuda.is_available()}


@app.post("/infer")
def infer(req: InferRequest):
    img_bytes = base64.b64decode(req.image_base64)
    img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    results = model(img, device=DEVICE, verbose=False, imgsz=448)
    count = 0
    boxes = []
    for r in results:
        count += len(r.boxes)
        for box in r.boxes:
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            boxes.append({
                "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                "conf": float(box.conf[0])
            })
    return {"eosinophil_count": count, "boxes": boxes}


@app.post("/infer_raw")
def infer_raw(req: TensorRequest):
    raw    = base64.b64decode(req.tensor_base64)
    tensor = np.frombuffer(raw, dtype=np.float32).reshape(1, 3, 448, 448)
    img    = tensor[0].transpose(1, 2, 0)
    img    = (img * 255).clip(0, 255).astype(np.uint8)

    results = model.predict(
        img,
        device=DEVICE,
        verbose=False,
        imgsz=448,
        save=False,
        save_txt=False,
    )

    # ── Валидная зона (исключаем дубли из нахлёста) ──────────────────────────
    # Т.к. patch_wsi_size == MODEL_SIZE == 448, масштаб = 1.0
    # Валидная зона: [half_ov .. 448-half_ov] по x и y
    half_ov = req.overlap_px / 2  # = 12.0 при overlap=24

    lo_x = half_ov if not req.edge_left   else 0.0
    lo_y = half_ov if not req.edge_top    else 0.0
    hi_x = (req.patch_wsi_size - half_ov) if not req.edge_right  else float(req.patch_wsi_size)
    hi_y = (req.patch_wsi_size - half_ov) if not req.edge_bottom else float(req.patch_wsi_size)
    # ─────────────────────────────────────────────────────────────────────────

    eos_count      = 0
    eosg_count     = 0
    valid_eos      = 0
    valid_eosg     = 0

    if results[0].boxes is not None:
        boxes_data = results[0].boxes
        for i, cls_id in enumerate(boxes_data.cls.cpu().numpy()):
            name = model.names[int(cls_id)]
            x1, y1, x2, y2 = boxes_data.xyxy[i].tolist()
            cx = (x1 + x2) / 2
            cy = (y1 + y2) / 2

            # Считаем все детекции (total)
            if name == "eos":
                eos_count += 1
            elif name == "eosg":
                eosg_count += 1

            # Считаем только попавшие в валидную зону (valid — без дублей)
            if lo_x <= cx <= hi_x and lo_y <= cy <= hi_y:
                if name == "eos":
                    valid_eos += 1
                elif name == "eosg":
                    valid_eosg += 1

    total_count = eos_count  + eosg_count
    valid_count = valid_eos  + valid_eosg

    logger.info(
        f"total=({eos_count}eos+{eosg_count}eosg={total_count}) "
        f"valid=({valid_eos}eos+{valid_eosg}eosg={valid_count}) "
        f"zone=({lo_x:.0f}-{hi_x:.0f}, {lo_y:.0f}-{hi_y:.0f})"
    )

    return {
        "eosinophil_count": valid_count,   # ← Java читает это поле
        "total_count":      total_count,
        "valid_count":      valid_count,
        "valid_eos":        valid_eos,
        "valid_eosg":       valid_eosg,
    }