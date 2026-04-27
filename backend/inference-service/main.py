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

@app.post("/infer_raw")
def infer_raw(req: TensorRequest):
    raw = base64.b64decode(req.tensor_base64)
    tensor = np.frombuffer(raw, dtype=np.float32).reshape(1, 3, 448, 448)
    
    import torch
    t = torch.from_numpy(tensor.copy()).to(DEVICE)
    
    # Получаем сырой output0 без постобработки
    with torch.no_grad():
        output = model.model(t)[0]  # [1, 116, 4116]
    
    out_np = output.cpu().numpy().flatten()
    out_b64 = base64.b64encode(out_np.astype(np.float32).tobytes()).decode()
    
    return {"output0_base64": out_b64}