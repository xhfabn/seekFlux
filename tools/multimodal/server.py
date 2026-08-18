#!/usr/bin/env python3
"""Versioned visual, OCR, ASR and caption understanding sidecar for SeekFlux."""

from __future__ import annotations

import os
import logging
import shutil
import subprocess
import tempfile
import threading
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Literal

import torch
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel, Field
from transformers import AutoModel, AutoProcessor


MODEL_ID = os.getenv("MULTIMODAL_MODEL_ID", "google/siglip2-base-patch16-224")
OCR_MODEL_VERSION = os.getenv("MULTIMODAL_OCR_MODEL_VERSION", "rapidocr-onnxruntime-1.4.4")
ASR_MODEL_ID = os.getenv("MULTIMODAL_ASR_MODEL_ID", "small")
CAPTION_MODEL_ID = os.getenv("MULTIMODAL_CAPTION_MODEL_ID", "Salesforce/blip-image-captioning-base")
MAX_DOWNLOAD_BYTES = int(os.getenv("MULTIMODAL_MAX_DOWNLOAD_BYTES", str(512 * 1024 * 1024)))
DEVICE = os.getenv("MULTIMODAL_DEVICE", "mps" if torch.backends.mps.is_available() else "cpu")
OCR_ENABLED = os.getenv("MULTIMODAL_OCR_ENABLED", "true").lower() == "true"
ASR_ENABLED = os.getenv("MULTIMODAL_ASR_ENABLED", "true").lower() == "true"
CAPTION_ENABLED = os.getenv("MULTIMODAL_CAPTION_ENABLED", "false").lower() == "true"

app = FastAPI(title="SeekFlux Multimodal Understanding", version="2.0")
processor = AutoProcessor.from_pretrained(MODEL_ID)
model = AutoModel.from_pretrained(MODEL_ID).to(DEVICE).eval()
_model_lock = threading.Lock()
_loader_lock = threading.Lock()
logger = logging.getLogger("seekflux.multimodal")
_ocr_engine: Any = None
_asr_engine: Any = None
_caption_processor: Any = None
_caption_model: Any = None


class MediaRequest(BaseModel):
    modality: Literal["TEXT", "IMAGE", "VIDEO"]
    input: str = Field(min_length=1, max_length=4096)
    maxSegments: int = Field(default=8, ge=1, le=32)


def normalized(values: torch.Tensor) -> list[float]:
    vector = torch.nn.functional.normalize(values, p=2, dim=-1)[0].detach().cpu()
    return [round(float(value), 8) for value in vector]


def text_vector(text: str) -> list[float]:
    inputs = processor(text=[text], padding="max_length", return_tensors="pt")
    inputs = {key: value.to(DEVICE) for key, value in inputs.items()}
    with _model_lock, torch.inference_mode():
        return normalized(model.get_text_features(**inputs))


def image_vector(path: Path) -> list[float]:
    with Image.open(path) as source:
        inputs = processor(images=[source.convert("RGB")], return_tensors="pt")
    inputs = {key: value.to(DEVICE) for key, value in inputs.items()}
    with _model_lock, torch.inference_mode():
        return normalized(model.get_image_features(**inputs))


def download(uri: str, directory: Path) -> Path:
    parsed = urllib.parse.urlparse(uri)
    if parsed.scheme not in {"http", "https"}:
        raise HTTPException(400, "media input must be an http(s) URI")
    destination = directory / (Path(parsed.path).name or "media.bin")
    request = urllib.request.Request(uri, headers={"User-Agent": "SeekFlux-Multimodal/2.0"})
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
        raise HTTPException(503, "ffmpeg is required for video understanding")
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


def embedding_segments(modality: str, media: Path | None, uri: str, directory: Path, limit: int) -> tuple[list[dict[str, Any]], list[Path]]:
    if modality == "TEXT":
        return [{"ordinal": 0, "startMillis": 0, "endMillis": 0, "previewUri": "",
                 "vector": text_vector(uri)}], []
    frames = [media] if modality == "IMAGE" else video_frames(media, directory, limit)  # type: ignore[arg-type]
    segments = []
    for index, frame in enumerate(frames):
        start = index * 5000 if modality == "VIDEO" else 0
        end = (index + 1) * 5000 if modality == "VIDEO" else 0
        segments.append({"ordinal": index, "startMillis": start, "endMillis": end,
                         "previewUri": uri, "vector": image_vector(frame)})
    return segments, frames


def evidence(channel: str, text: str, confidence: float, start: int, end: int, version: str) -> dict[str, Any]:
    return {"channel": channel, "text": text.strip(), "confidence": round(float(confidence), 4),
            "startMillis": start, "endMillis": end, "modelVersion": version}


def extract_ocr(frames: list[Path], modality: str) -> list[dict[str, Any]]:
    global _ocr_engine
    if _ocr_engine is None:
        with _loader_lock:
            if _ocr_engine is None:
                from rapidocr_onnxruntime import RapidOCR
                _ocr_engine = RapidOCR()
    values: list[dict[str, Any]] = []
    for index, frame in enumerate(frames):
        result, _ = _ocr_engine(str(frame))
        if not result:
            continue
        text = " ".join(str(line[1]).strip() for line in result if str(line[1]).strip())
        confidences = [float(line[2]) for line in result]
        if text:
            start = index * 5000 if modality == "VIDEO" else 0
            end = (index + 1) * 5000 if modality == "VIDEO" else 0
            values.append(evidence("OCR", text, sum(confidences) / len(confidences), start, end,
                                   OCR_MODEL_VERSION))
    return values


def extract_asr(media: Path) -> list[dict[str, Any]]:
    global _asr_engine
    if _asr_engine is None:
        with _loader_lock:
            if _asr_engine is None:
                from faster_whisper import WhisperModel
                _asr_engine = WhisperModel(ASR_MODEL_ID, device="cpu", compute_type="int8")
    segments, _ = _asr_engine.transcribe(str(media), vad_filter=True)
    values = []
    for segment in segments:
        text = segment.text.strip()
        if text:
            confidence = max(0.0, min(1.0, 1.0 - float(segment.no_speech_prob)))
            values.append(evidence("ASR", text, confidence, int(segment.start * 1000),
                                   int(segment.end * 1000), f"faster-whisper-{ASR_MODEL_ID}"))
    return values


def extract_captions(frames: list[Path], modality: str) -> list[dict[str, Any]]:
    global _caption_processor, _caption_model
    if _caption_model is None:
        with _loader_lock:
            if _caption_model is None:
                from transformers import BlipForConditionalGeneration, BlipProcessor
                _caption_processor = BlipProcessor.from_pretrained(CAPTION_MODEL_ID)
                _caption_model = BlipForConditionalGeneration.from_pretrained(CAPTION_MODEL_ID).to("cpu").eval()
    values = []
    for index, frame in enumerate(frames):
        with Image.open(frame) as source:
            inputs = _caption_processor(images=source.convert("RGB"), return_tensors="pt")
        with _model_lock, torch.inference_mode():
            output = _caption_model.generate(**inputs, max_new_tokens=48)
        text = _caption_processor.decode(output[0], skip_special_tokens=True).strip()
        if text:
            start = index * 5000 if modality == "VIDEO" else 0
            end = (index + 1) * 5000 if modality == "VIDEO" else 0
            values.append(evidence("CAPTION", text, 0.7, start, end, CAPTION_MODEL_ID))
    return values


def run_channel(name: str, enabled: bool, operation: Any) -> tuple[list[dict[str, Any]], str]:
    if not enabled:
        return [], "DISABLED"
    try:
        return operation(), "AVAILABLE"
    except Exception:
        # A channel failure must not discard successful visual vectors or other evidence lanes.
        logger.exception("understanding channel %s degraded", name)
        return [], "DEGRADED"


def analyze(request: MediaRequest, include_understanding: bool) -> dict[str, Any]:
    if request.modality == "TEXT":
        segments, _ = embedding_segments("TEXT", None, request.input, Path("."), 1)
        return {"modelVersion": MODEL_ID, "dimensions": len(segments[0]["vector"]),
                "segments": segments, "evidence": [], "channelStatuses": {"VISUAL": "AVAILABLE"}}
    with tempfile.TemporaryDirectory(prefix="seekflux-mm-") as raw_directory:
        directory = Path(raw_directory)
        media = download(request.input, directory)
        segments, frames = embedding_segments(request.modality, media, request.input, directory, request.maxSegments)
        all_evidence: list[dict[str, Any]] = []
        statuses = {"VISUAL": "AVAILABLE"}
        if include_understanding:
            ocr, statuses["OCR"] = run_channel("OCR", OCR_ENABLED,
                                                lambda: extract_ocr(frames, request.modality))
            all_evidence.extend(ocr)
            asr, statuses["ASR"] = run_channel("ASR", ASR_ENABLED and request.modality == "VIDEO",
                                                lambda: extract_asr(media))
            all_evidence.extend(asr)
            captions, statuses["CAPTION"] = run_channel("CAPTION", CAPTION_ENABLED,
                                                        lambda: extract_captions(frames, request.modality))
            all_evidence.extend(captions)
        return {"modelVersion": MODEL_ID, "dimensions": len(segments[0]["vector"]),
                "segments": segments, "evidence": all_evidence, "channelStatuses": statuses}


@app.get("/health")
def health() -> dict[str, object]:
    return {"status": "UP", "modelVersion": MODEL_ID, "device": DEVICE,
            "channels": {"VISUAL": "ENABLED", "OCR": "ENABLED" if OCR_ENABLED else "DISABLED",
                         "ASR": "ENABLED" if ASR_ENABLED else "DISABLED",
                         "CAPTION": "ENABLED" if CAPTION_ENABLED else "DISABLED"}}


@app.post("/v1/embeddings")
def embeddings(request: MediaRequest) -> dict[str, Any]:
    return analyze(request, False)


@app.post("/v1/understand")
def understand(request: MediaRequest) -> dict[str, Any]:
    if request.modality == "TEXT":
        raise HTTPException(400, "understanding requires IMAGE or VIDEO")
    return analyze(request, True)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=int(os.getenv("MULTIMODAL_MODEL_PORT", "8090")))
