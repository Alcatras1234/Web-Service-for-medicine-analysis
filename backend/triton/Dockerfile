FROM nvcr.io/nvidia/tritonserver:24.09-py3

# torch + numpy для Python-backend моделей (preprocess/postprocess), если они есть
# в каталоге /models. Самой seg-модели (ONNX) ничего из этого не нужно — она крутится
# на onnxruntime_onnx бекенде, который встроен в triton:24.09-py3 из коробки.
RUN pip install --no-cache-dir \
    torch==2.4.1+cu121 \
    torchvision==0.19.1+cu121 \
    --index-url https://download.pytorch.org/whl/cu121 && \
    pip install --no-cache-dir numpy==1.24.4
