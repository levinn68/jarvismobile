# JARVIS VoxCPM2 inference service

GPU-backed voice cloning service for the JARVIS Worker. It loads `openbmb/VoxCPM2` once at startup and accepts authenticated synthesis requests.

## Requirements
- NVIDIA GPU with CUDA 12+ and at least 16 GB VRAM recommended for operational headroom.
- Set `VOXCPM_API_KEY` to a long random secret.
- Expose the service only over HTTPS; never expose it publicly without the bearer token.

## Deploy
Build this directory as a Docker image and deploy it to a GPU container provider. Configure:
- `VOXCPM_API_KEY`
- optional `VOXCPM_MODEL` (defaults to `openbmb/VoxCPM2`)

Set the Cloudflare Worker secrets/vars:
- `VOXCPM_URL` = service origin, e.g. `https://your-gpu-service.example`
- `VOXCPM_API_KEY` = same secret

## API
`POST /v1/audio/speech` with Bearer auth and JSON fields:
- `text` (1–1800 chars)
- `reference_audio_base64` (mono WAV, 16 kHz, 2–30 seconds, max 8 MB)
- optional `prompt_text` (exact transcript of the reference clip; enables continuation-style cloning)
- optional `style`, `seed`

Returns WAV audio. `GET /health` reports model load state.

The model is not bundled in the repository; it downloads from Hugging Face on first startup. Pin the model revision and container image digest before production.
