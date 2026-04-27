from fastapi import FastAPI
from pydantic import BaseModel
from ultralytics import YOLO
from PIL import Image
import base64, io, torch, numpy as np

app = FastAPI()

MODEL_PATH = "./triton-models/eosin_yolo/1/best.pt"
DEVICE = 0 if torch.cuda.is_available() else "cpu"
print(f"Loading model on device: {DEVICE}")
model = YOLO(MODEL_PATH)


class InferRequest(BaseModel):
    image_base64: str


class TensorRequest(BaseModel):
    tensor_base64: str


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
    raw = base64.b64decode(req.tensor_base64)

    # NCHW float32[0-1] → HWC uint8[0-255], который ожидает YOLO
    tensor = np.frombuffer(raw, dtype=np.float32).reshape(1, 3, 448, 448)
    img = tensor[0].transpose(1, 2, 0)                  # (448, 448, 3)
    img = (img * 255).clip(0, 255).astype(np.uint8)     # float → uint8

    results = model.predict(img, device=DEVICE, verbose=False, imgsz=448)

    count = sum(len(r.boxes) for r in results)
    return {"eosinophil_count": count}