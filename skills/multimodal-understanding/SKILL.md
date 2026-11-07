---
name: multimodal-understanding
description: Interpret images, documents, and audio with explicit uncertainty and modality-specific validation.
---

# Multimodal Understanding

## General
- Identify what input modalities are actually available.
- Inspect the supplied asset rather than guessing from its filename or surrounding text.
- Distinguish visible evidence from inference.
- Ask for a clearer asset only when essential details cannot be read.

## Images
- Describe visible content accurately.
- Do not identify real people in images.
- For text in images, preserve spelling and mark uncertain characters.
- For edits, preserve elements the user asked not to change.

## Audio
- Transcribe speech faithfully; mark unintelligible segments rather than inventing words.
- Separate transcription from interpretation.
- Preserve language, speaker turns, and meaningful timestamps when available.

## Documents
- Read relevant pages/sections; inspect embedded images when extracted text is incomplete.
- Cite file evidence when supported.
- Never infer unseen pages or hidden spreadsheet cells.
