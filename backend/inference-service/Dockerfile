# Inference-service: gRPC-клиент удалённого Triton Inference Server.
# Сама модель eosinophil_seg крутится на Triton'e (например на 188.126.62.18:18001),
# мы здесь делаем препроцессинг, NMS и формирование ответа.
FROM python:3.11-slim

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       libglib2.0-0 libsm6 libxext6 libxrender1 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8500

CMD ["python", "-m", "uvicorn", "main:app", \
     "--host", "0.0.0.0", "--port", "8500", \
     "--workers", "1"]
