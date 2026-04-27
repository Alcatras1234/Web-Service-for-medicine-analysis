from fastapi import FastAPI
from pydantic import BaseModel
from ultralytics import YOLO
from PIL import Image
import base64, io, torch

app = FastAPI()

MODEL_PATH = "./triton-models/eosin_yolo/1/model.onnx"  # или best.pt
DEVICE = 0 if torch.cuda.is_available() else "cpu"

print(f"Loading model on device: {DEVICE}")
model = YOLO(MODEL_PATH)

class InferRequest(BaseModel):
    image_base64: str

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