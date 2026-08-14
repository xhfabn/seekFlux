#!/usr/bin/env python3
"""SigLIP-backed text/image/video embedding sidecar for SeekFlux."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Literal

import torch
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel, Field
from transformers import AutoModel, AutoProcessor


MODEL_ID = os.getenv("MULTIMODAL_MODEL_ID", "google/siglip2-base-patch16-224")
MAX_DOWNLOAD_BYTES = int(os.getenv("MULTIMODAL_MAX_DOWNLOAD_BYTES", str(512 * 1024 * 1024)))
DEVICE = os.getenv("MULTIMODAL_DEVICE", "mps" if torch.backends.mps.is_available() else "cpu")

app = FastAPI(title="SeekFlux Multimodal Model", version="1.0")
processor = AutoProcessor.from_pretrained(MODEL_ID)
model = AutoModel.from_pretrained(MODEL_ID).to(DEVICE).eval()


class EmbeddingRequest(BaseModel):
    modality: Literal["TEXT", "IMAGE", "VIDEO"]
    input: str = Field(min_length=1, max_length=4096)
    maxSegments: int = Field(default=8, ge=1, le=32)


def normalized(values: torch.Tensor) -> list[float]:
    vector = torch.nn.functional.normalize(values, p=2, dim=-1)[0].detach().cpu()
    return [round(float(value), 8) for value in vector]


def text_vector(text: str) -> list[float]:
    inputs = processor(text=[text], padding="max_length", return_tensors="pt")
    inputs = {key: value.to(DEVICE) for key, value in inputs.items()}
    with torch.inference_mode():
        return normalized(model.get_text_features(**inputs))


def image_vector(path: Path) -> list[float]:
    with Image.open(path) as source:
        image = source.convert("RGB")
        inputs = processor(images=[image], return_tensors="pt")
    inputs = {key: value.to(DEVICE) for key, value in inputs.items()}
    with torch.inference_mode():
        return normalized(model.get_image_features(**inputs))


def download(uri: str, directory: Path) -> Path:
    parsed = urllib.parse.urlparse(uri)
    if parsed.scheme not in {"http", "https"}:
        raise HTTPException(400, "media input must be an http(s) URI")
    destination = directory / (Path(parsed.path).name or "media.bin")
    request = urllib.request.Request(uri, headers={"User-Agent": "SeekFlux-Multimodal/1.0"})
    total = 0
    try:
        with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > MAX_DOWNLOAD_BYTES:
                    raise HTTPException(413, "media exceeds configured download limit")
                output.write(chunk)
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(422, f"cannot fetch media: {error}") from error
    return destination


def video_frames(video: Path, directory: Path, limit: int) -> list[Path]:
    if shutil.which("ffmpeg") is None:
        raise HTTPException(503, "ffmpeg is required for video embeddings")
    frames = directory / "frames"
    frames.mkdir()
    result = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(video), "-vf", "fps=1/5,scale=768:-2",
         "-frames:v", str(limit), str(frames / "%04d.jpg")],
        capture_output=True, text=True, timeout=180, check=False,
    )
    if result.returncode != 0:
        raise HTTPException(422, f"cannot decode video: {result.stderr[-300:]}")
    values = sorted(frames.glob("*.jpg"))
    if not values:
        raise HTTPException(422, "video produced no keyframes")
    return values


@app.get("/health")
def health() -> dict[str, object]:
    return {"status": "UP", "modelVersion": MODEL_ID, "device": DEVICE}


@app.post("/v1/embeddings")
def embeddings(request: EmbeddingRequest) -> dict[str, object]:
    if request.modality == "TEXT":
        vectors = [(0, 0, 0, "", text_vector(request.input))]
    else:
        with tempfile.TemporaryDirectory(prefix="seekflux-mm-") as raw_directory:
            directory = Path(raw_directory)
            media = download(request.input, directory)
            if request.modality == "IMAGE":
                vectors = [(0, 0, 0, request.input, image_vector(media))]
            else:
                frames = video_frames(media, directory, request.maxSegments)
                vectors = [
                    (index, index * 5000, (index + 1) * 5000, request.input, image_vector(frame))
                    for index, frame in enumerate(frames)
                ]
    dimensions = len(vectors[0][4])
    return {
        "modelVersion": MODEL_ID,
        "dimensions": dimensions,
        "segments": [
            {"ordinal": ordinal, "startMillis": start, "endMillis": end,
             "previewUri": preview, "vector": vector}
            for ordinal, start, end, preview, vector in vectors
        ],
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=int(os.getenv("MULTIMODAL_MODEL_PORT", "8090")))
