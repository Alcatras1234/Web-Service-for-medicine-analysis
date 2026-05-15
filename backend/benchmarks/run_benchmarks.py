"""
Бенчмарк ключевых оптимизаций EosinAI.

Запускается ПРОТИВ работающего стека (docker compose должен быть up).
Не правит production-код, а реально стучит в endpoints и MinIO,
измеряет реальное wall-clock время.

Что проверяет:
  1. БАТЧИНГ инференса:
       16 × POST /infer_raw  (последовательно)  VS  1 × POST /infer_batch (16 патчей в одном)
  2. ПАРАЛЛЕЛЬНОЕ скачивание из MinIO:
       16 загрузок последовательно (1 поток)    VS  16 загрузок в 8 потоков
  3. SKIP белых патчей:
       16 контентных патчей через /infer_batch  VS  16 БЕЛЫХ патчей (триггерят skip)

Запуск:
  python run_benchmarks.py
  # или с кастомными адресами:
  python run_benchmarks.py --inference http://localhost:18500 --minio http://localhost:19000

Результат: таблица в консоли + bench_results.json для возможной визуализации.
"""

import argparse
import base64
import concurrent.futures
import json
import os
import statistics
import sys
import time
from typing import List, Tuple

import numpy as np
import requests
from minio import Minio


# ── Конфиг ────────────────────────────────────────────────────────────────────
DEFAULT_INFERENCE = "http://localhost:18500"
DEFAULT_MINIO_HOST = "localhost:19000"
DEFAULT_MINIO_KEY = "minioadmin"
DEFAULT_MINIO_SECRET = "minioadmin"
DEFAULT_BUCKET = "wsi-bucket"

PATCH_SIZE = 448
BATCH_SIZE = 16
N_REPEATS = 3   # каждый замер прогоняем N раз и берём медиану — уменьшаем шум


# ── Генерация патчей ──────────────────────────────────────────────────────────
def make_content_patch(seed: int) -> bytes:
    """Псевдо-биоптатный патч 448×448 RGB uint8 — розовые тона H&E."""
    rng = np.random.default_rng(seed)
    # Базовый розовый фон ~(220, 180, 200) с шумом
    img = np.zeros((PATCH_SIZE, PATCH_SIZE, 3), dtype=np.uint8)
    img[..., 0] = 220 + rng.integers(-25, 25, (PATCH_SIZE, PATCH_SIZE), dtype=np.int8)
    img[..., 1] = 180 + rng.integers(-30, 30, (PATCH_SIZE, PATCH_SIZE), dtype=np.int8)
    img[..., 2] = 200 + rng.integers(-25, 25, (PATCH_SIZE, PATCH_SIZE), dtype=np.int8)
    # Несколько "клеток" — тёмные пятна
    for _ in range(rng.integers(5, 30)):
        cx, cy = rng.integers(20, PATCH_SIZE - 20, 2)
        r = rng.integers(5, 12)
        img[cy - r:cy + r, cx - r:cx + r] = rng.integers(60, 120, 3)
    return img.tobytes()


def make_white_patch() -> bytes:
    """Белый патч (стекло) — mean ≈ 250, триггерит white-skip."""
    img = np.full((PATCH_SIZE, PATCH_SIZE, 3), 250, dtype=np.uint8)
    img += np.random.default_rng(42).integers(-3, 3, img.shape, dtype=np.int8).astype(np.uint8)
    return img.tobytes()


def encode_patch(raw: bytes) -> str:
    return base64.b64encode(raw).decode()


def make_patch_request(b64: str, patch_id: str = "test") -> dict:
    return {
        "patch_id": patch_id,
        "tensor_base64": b64,
        "patch_wsi_size": PATCH_SIZE,
        "overlap_px": 24,
        "edge_left": False, "edge_top": False, "edge_right": False, "edge_bottom": False,
    }


# ── Бенчмарк 1: батчинг инференса ─────────────────────────────────────────────
def bench_sequential_inference(url: str, patches: List[bytes]) -> float:
    """16 отдельных POST /infer_raw — каждый со своим HTTP-overhead."""
    t0 = time.perf_counter()
    for p in patches:
        body = {
            "tensor_base64": encode_patch(p),
            "patch_wsi_size": PATCH_SIZE,
            "overlap_px": 24,
            "edge_left": False, "edge_top": False, "edge_right": False, "edge_bottom": False,
        }
        r = requests.post(f"{url}/infer_raw", json=body, timeout=120)
        r.raise_for_status()
    return time.perf_counter() - t0


def bench_batch_inference(url: str, patches: List[bytes]) -> float:
    """1 POST /infer_batch с 16 патчами внутри."""
    body = {
        "patches": [make_patch_request(encode_patch(p), f"p_{i}") for i, p in enumerate(patches)],
    }
    t0 = time.perf_counter()
    r = requests.post(f"{url}/infer_batch", json=body, timeout=120)
    r.raise_for_status()
    return time.perf_counter() - t0


# ── Бенчмарк 2: параллельная загрузка из MinIO ────────────────────────────────
def bench_sequential_download(client: Minio, bucket: str, keys: List[str]) -> float:
    t0 = time.perf_counter()
    for k in keys:
        with client.get_object(bucket, k) as r:
            _ = r.read()
    return time.perf_counter() - t0


def bench_parallel_download(client: Minio, bucket: str, keys: List[str], threads: int = 8) -> float:
    def fetch(k):
        with client.get_object(bucket, k) as r:
            return r.read()

    t0 = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=threads) as ex:
        list(ex.map(fetch, keys))
    return time.perf_counter() - t0


# ── Бенчмарк 3: skip белых патчей ─────────────────────────────────────────────
def bench_white_skip(url: str, count: int) -> float:
    """Все патчи белые → inference-сервис должен сразу вернуть пустой результат."""
    body = {
        "patches": [make_patch_request(encode_patch(make_white_patch()), f"w_{i}") for i in range(count)],
    }
    t0 = time.perf_counter()
    r = requests.post(f"{url}/infer_batch", json=body, timeout=60)
    r.raise_for_status()
    return time.perf_counter() - t0


# ── Main ──────────────────────────────────────────────────────────────────────
def median_of(fn, n: int = N_REPEATS) -> Tuple[float, float, float]:
    """Возвращает (медиана, мин, макс) wall-clock в секундах за n прогонов."""
    times = [fn() for _ in range(n)]
    return statistics.median(times), min(times), max(times)


def fmt_t(seconds: float) -> str:
    if seconds < 1:
        return f"{seconds * 1000:6.1f} мс"
    return f"{seconds:6.2f} c "


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inference", default=DEFAULT_INFERENCE)
    ap.add_argument("--minio-host", default=DEFAULT_MINIO_HOST)
    ap.add_argument("--minio-key", default=DEFAULT_MINIO_KEY)
    ap.add_argument("--minio-secret", default=DEFAULT_MINIO_SECRET)
    ap.add_argument("--bucket", default=DEFAULT_BUCKET)
    ap.add_argument("--skip-minio", action="store_true", help="не тестить MinIO downloads")
    ap.add_argument("--n-patches", type=int, default=BATCH_SIZE)
    args = ap.parse_args()

    print("=" * 70)
    print("EosinAI benchmark — оптимизации производительности")
    print("=" * 70)
    print(f"inference: {args.inference}")
    print(f"minio:     {args.minio_host}")
    print(f"patches:   {args.n_patches} штук")
    print(f"повторы:   {N_REPEATS} (берём медиану)")
    print()

    # Health-check
    try:
        h = requests.get(f"{args.inference}/health", timeout=5).json()
        print(f"✓ inference жив: model_ready={h.get('model_ready')}")
    except Exception as e:
        print(f"✗ inference не отвечает: {e}")
        sys.exit(1)

    results = {}

    # Генерим патчи один раз
    content_patches = [make_content_patch(i) for i in range(args.n_patches)]

    # ── 1. БАТЧИНГ ───────────────────────────────────────────────────────────
    print()
    print("[1/3] Батчинг инференса (16 патчей)")
    print("-" * 70)
    med_seq, mn_seq, mx_seq = median_of(lambda: bench_sequential_inference(args.inference, content_patches))
    med_bat, mn_bat, mx_bat = median_of(lambda: bench_batch_inference(args.inference, content_patches))
    speedup_bat = med_seq / med_bat
    print(f"  Последовательно (16 × /infer_raw):  {fmt_t(med_seq)}  [min {fmt_t(mn_seq)} / max {fmt_t(mx_seq)}]")
    print(f"  Батчем          (1 × /infer_batch): {fmt_t(med_bat)}  [min {fmt_t(mn_bat)} / max {fmt_t(mx_bat)}]")
    print(f"  → УСКОРЕНИЕ: ×{speedup_bat:.2f}")
    results["batching"] = {
        "sequential_median_s": med_seq, "batch_median_s": med_bat, "speedup": speedup_bat,
    }

    # ── 2. ПАРАЛЛЕЛЬНАЯ ЗАГРУЗКА ИЗ MINIO ────────────────────────────────────
    if not args.skip_minio:
        print()
        print("[2/3] Параллельная загрузка из MinIO (16 объектов)")
        print("-" * 70)
        client = Minio(args.minio_host, access_key=args.minio_key,
                       secret_key=args.minio_secret, secure=False)
        # bucket гарантируем
        if not client.bucket_exists(args.bucket):
            client.make_bucket(args.bucket)
        # заливаем тестовые объекты
        keys = []
        from io import BytesIO
        for i, p in enumerate(content_patches):
            key = f"bench/patch_{i}.raw"
            client.put_object(args.bucket, key, BytesIO(p), len(p))
            keys.append(key)

        med_s, mn_s, mx_s = median_of(lambda: bench_sequential_download(client, args.bucket, keys))
        med_p, mn_p, mx_p = median_of(lambda: bench_parallel_download(client, args.bucket, keys, threads=8))
        speedup_dl = med_s / med_p
        print(f"  Последовательно (1 поток): {fmt_t(med_s)}  [min {fmt_t(mn_s)} / max {fmt_t(mx_s)}]")
        print(f"  Параллельно     (8 потоков): {fmt_t(med_p)}  [min {fmt_t(mn_p)} / max {fmt_t(mx_p)}]")
        print(f"  → УСКОРЕНИЕ: ×{speedup_dl:.2f}")
        results["minio_download"] = {
            "sequential_median_s": med_s, "parallel_median_s": med_p, "speedup": speedup_dl,
        }

        # подчистим за собой
        for k in keys:
            try: client.remove_object(args.bucket, k)
            except Exception: pass

    # ── 3. SKIP БЕЛЫХ ПАТЧЕЙ ─────────────────────────────────────────────────
    print()
    print("[3/3] Эффект skip белых патчей (16 патчей)")
    print("-" * 70)
    med_content, _, _ = median_of(lambda: bench_batch_inference(args.inference, content_patches))
    med_white, _, _ = median_of(lambda: bench_white_skip(args.inference, args.n_patches))
    saved_pct = (1 - med_white / med_content) * 100
    print(f"  Все патчи КОНТЕНТНЫЕ:  {fmt_t(med_content)}")
    print(f"  Все патчи БЕЛЫЕ (skip): {fmt_t(med_white)}")
    print(f"  → ЭКОНОМИЯ: {saved_pct:.1f}% времени  (model не вызывается на белом фоне)")
    results["white_skip"] = {
        "content_median_s": med_content, "white_median_s": med_white, "saved_pct": saved_pct,
    }

    # ── Итог ─────────────────────────────────────────────────────────────────
    print()
    print("=" * 70)
    print("ИТОГИ")
    print("=" * 70)
    print(f"  Батчинг:                   ×{results['batching']['speedup']:.2f}")
    if "minio_download" in results:
        print(f"  Параллельная загрузка:     ×{results['minio_download']['speedup']:.2f}")
    print(f"  Skip белых патчей:         −{results['white_skip']['saved_pct']:.1f}% (на белом стекле)")
    print()

    # JSON для графиков
    out_path = "bench_results.json"
    with open(out_path, "w") as f:
        json.dump(results, f, indent=2)
    print(f"  Подробности → {os.path.abspath(out_path)}")


if __name__ == "__main__":
    main()
