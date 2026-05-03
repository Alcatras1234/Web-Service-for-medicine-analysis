FROM nvidia/cuda:11.8.0-cudnn8-runtime-ubuntu22.04

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && ln -sf python3 /usr/bin/python \
    && ln -sf pip3 /usr/bin/pip

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ENV MODEL_PATH=/app/triton-models/eosin_yolo/1/best.onnx
EXPOSE 8500

CMD ["python", "-m", "uvicorn", "main:app", \
     "--host", "0.0.0.0", "--port", "8500", \
     "--workers", "1"]