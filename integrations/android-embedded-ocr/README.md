# Embedded document capabilities

Optional artifact `io.github.daniele21.localllm:embedded-ocr-android` provides bundled ML Kit Latin OCR, bounded image input and normalized line rectangles. It requires no Qwen projector or model download. Cancellation discards late results; a running native task may complete before the same recognizer instance is available again.

The existing embedded Android SDK also exposes additive `previewStructured` and `generateStructured` methods, using the same runtime and schema-constrained preset. Consumers must check model readiness, preview the exact request, enforce completion and validate semantic evidence themselves.

This candidate requires Maven staging, Android compilation and physical-device verification. It does not change the existing vision encoder or claim to repair its performance.
