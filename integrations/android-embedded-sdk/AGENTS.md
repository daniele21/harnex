# Embedded Android SDK — Coding Agent Guide

## Scope

This guide owns `integrations/android-embedded-sdk`, the in-process integration adapter for independently distributed Android applications. Follow the [root agent guide](../../AGENTS.md) first.

## Invariants

- Reuse `core/runtime-core`, `transports/in-process`, the model lifecycle and `backends/llama-cpp`. Do not introduce an independent runtime scheduler, cache owner or native backend.
- The embedding application owns conversations, selected message history, user-facing UI and product persistence. Harnex receives bounded `GenerationInput.Messages` and maintains only technical runtime sessions.
- The public embedded path is app-private and does not bind to a Host or obtain Binder signer authorization. No cloud fallback or prompt/output logging.
- Model selection is explicit, limited to reviewed curated artifacts; model install, readiness and runtime prepare remain distinct.
- Off-main lifecycle, cancellation, one-shot terminal events and cleanup on all exits are mandatory. Native/physical support claims need representative ARM64/GGUF evidence.

## Validation

Start with `:integrations:android-embedded-sdk:testDebugUnitTest`, `:integrations:android-embedded-sdk:lintDebug`, existing runtime/backends tests, and Gradle module/packaging verification. Public artifact work requires a clean external Maven consumer fixture and repository-selected STRONG/FULL validation. Emulator-only evidence is never physical Android proof.
