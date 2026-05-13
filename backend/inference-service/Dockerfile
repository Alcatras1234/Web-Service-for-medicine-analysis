# Лёгкий python-base — модель крутится на удалённом Triton, локальный CUDA не нужен.
FROM python:3.11-slim

WORKDIR /app

# Системные зависимости для opencv-python-headless
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       libglib2.0-0 libsm6 libxext6 libxrender1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8500

CMD ["python", "-m", "uvicorn", "main:app", \
     "--host", "0.0.0.0", "--port", "8500", \
     "--workers", "1"]
