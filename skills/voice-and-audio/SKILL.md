---
name: voice-and-audio
description: Design reliable voice interaction including speech recognition, turn-taking, TTS, language matching, latency, and audio failure recovery.
---

# Voice Interaction & Audio Systems

## Voice pipeline
Treat voice as a sequence: microphone permission → capture → speech detection/endpointing → transcription → intent/model response → TTS synthesis → playback → interruption handling.

## Language and voice
- Detect spoken language and keep response text and TTS language aligned.
- Test the selected voice with representative Indonesian and English phrases; do not assume multilingual quality from a model label.
- Preserve a consistent voice identity where the provider supports it.
- Avoid awkward literal translations and overly long spoken responses.
- Keep technical tokens and names pronounceable without changing their meaning.

## Reliability
- Handle silence, partial transcripts, noisy audio, duplicate events, timeouts, provider errors, and playback interruption.
- Prevent overlapping TTS playback and duplicate assistant turns.
- Separate STT failure, model failure, TTS failure, and device playback failure in logs.
- Do not report that audio was heard or played unless the pipeline confirms it.
- Provide a clear fallback to text when voice cannot complete.