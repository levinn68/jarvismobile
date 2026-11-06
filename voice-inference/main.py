import base64
import os
import tempfile
import threading
from pathlib import Path

import numpy as np
import soundfile as sf
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field
from voxcpm import VoxCPM

MODEL_ID = os.getenv("VOXCPM_MODEL", "openbmb/VoxCPM2")
API_KEY = os.getenv("VOXCPM_API_KEY", "")
MAX_REFERENCE_BYTES = 8 * 1024 * 1024
app = FastAPI(title="JARVIS VoxCPM2 Inference", version="1.0.0")
model = None
model_lock = threading.Lock()
generation_lock = threading.Lock()


class SpeechRequest(BaseModel):
    text: str = Field(min_length=1, max_length=1800)
    reference_audio_base64: str = Field(min_length=16)
    prompt_text: str | None = Field(default=None, max_length=1200)
    style: str | None = Field(default=None, max_length=240)
    seed: int | None = 42


@app.on_event("startup")
def load_model():
    global model
    with model_lock:
        if model is None:
            model = VoxCPM.from_pretrained(MODEL_ID, load_denoiser=False)


@app.get("/health")
def health():
    return {"ok": True, "model": MODEL_ID, "loaded": model is not None}


@app.post("/v1/audio/speech")
def synthesize(body: SpeechRequest, authorization: str | None = Header(default=None)):
    if not API_KEY or authorization != f"Bearer {API_KEY}":
        raise HTTPException(status_code=401, detail="Unauthorized")
    try:
        reference_bytes = base64.b64decode(body.reference_audio_base64, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid reference audio encoding")
    if not reference_bytes or len(reference_bytes) > MAX_REFERENCE_BYTES:
        raise HTTPException(status_code=413, detail="Reference audio must be under 8 MB")

    with tempfile.TemporaryDirectory(prefix="jarvis-voice-") as temp_dir:
        reference_path = Path(temp_dir) / "reference.wav"
        output_path = Path(temp_dir) / "output.wav"
        reference_path.write_bytes(reference_bytes)
        try:
            info = sf.info(str(reference_path))
            if info.duration < 2 or info.duration > 30:
                raise HTTPException(status_code=400, detail="Reference audio must be 2–30 seconds")
            if info.samplerate != 16000:
                audio, sr = sf.read(str(reference_path), dtype="float32")
                if audio.ndim > 1:
                    audio = np.mean(audio, axis=1)
                import librosa
                audio = librosa.resample(audio, orig_sr=sr, target_sr=16000)
                sf.write(str(reference_path), audio, 16000)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(status_code=400, detail="Reference must be a readable WAV audio file")

        target_text = body.text
        if body.style:
            target_text = f"({body.style}){target_text}"
        try:
            with generation_lock:
                kwargs = {
                    "text": target_text,
                    "reference_wav_path": str(reference_path),
                    "cfg_value": 2.0,
                    "inference_timesteps": 10,
                }
                if body.seed is not None:
                    kwargs["seed"] = body.seed
                if body.prompt_text:
                    kwargs["prompt_wav_path"] = str(reference_path)
                    kwargs["prompt_text"] = body.prompt_text
                wav = model.generate(**kwargs)
                sf.write(str(output_path), wav, model.tts_model.sample_rate, format="WAV")
            return Response(
                content=output_path.read_bytes(),
                media_type="audio/wav",
                headers={"Cache-Control": "no-store", "X-Jarvis-Voice-Engine": "VoxCPM2"},
            )
        except Exception as exc:
            raise HTTPException(status_code=502, detail=f"VoxCPM2 synthesis failed: {str(exc)[:240]}")
