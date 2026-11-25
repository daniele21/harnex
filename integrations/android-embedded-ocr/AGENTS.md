# Embedded OCR module — coding agent guide

## Scope

`integrations/android-embedded-ocr` is the optional, in-process Latin text-recognition Android integration. Read the repository root `AGENTS.md` and this module's `README.md` before changing its public contract.

## Ownership and boundaries

- Keep this artifact opt-in: apps that do not require OCR must not acquire the ML Kit dependency transitively.
- This module only normalizes image/text recognition and region evidence. It does not own Qwen inference, model downloads, scheduling, chat history or property-specific extraction.
- The app owns consent, page selection, document persistence and human review; Harnex embedded runtime owns structured generation and its lifecycle.
- OCR returns untrusted text and recognized line geometry, not independently verified facts. Never fabricate bounding boxes or document fields.
- Preserve local-only processing, bounded input, cancellation safety and no sensitive text in logs.

## Validation

Run applicable module compilation/lint, exact-artifact staged Maven consumption, and the repository's selected CI gates. Validate an actual ARM64 document journey before claiming physical-device OCR quality.
