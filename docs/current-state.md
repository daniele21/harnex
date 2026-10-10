# Current repository state

Status: active
Document type: current-state
Owner: repository
Canonical scope: state.repository
Read when: determining the integrated baseline, open blockers or next repository work block
Last reviewed: 2026-09-09

This is the operational ledger for integrated state, blockers and immediate work. Capability history belongs in [`roadmap.md`](roadmap.md); milestone detail stays in focused workstreams.

## Integration lines

- `dev` is the canonical base/target for ordinary work and Internal Testing candidates; `main` is the stable/release line.
- New work starts from the latest green `dev` unless explicitly hotfixed.
- Stable promotions preserve `main` ancestry in `dev` before the next `dev -> main` cycle, per ADR 0008.
- Repository governance tracks `repo-template-sw` `0.11.0` with the local Android/local-AI/product-UI customizations in `.engineering/baseline.json`.

## Integrated baseline

### Runtime, product and control plane

Harnex has pinned `llama.cpp`, reproducible Android `arm64-v8a` packaging, verified GGUF installation, model/generation lifecycle, cancellation, scheduling, memory-pressure handling, model-aware planning, output constraints and presets. Product support is curated Qwen3.5 dense 0.8B/2B plus the reviewed 4B **4-bit-only** candidate tier; 4B remains `CANDIDATE` pending exact-artifact representative-device runtime, memory, thermal and output-quality evidence. Q35-6 still needs representative-device tuning evidence.

`apps/local-llm-phone-test` exposes Overview, Playground, Activity, Applications, Performance, Models, Diagnostics and Settings. Applications control-plane work is complete through ACUX-80 and CPREC-10..70; broader representative-device UX/runtime evidence remains.

### Shared runtime and Consumer boundary

SR-0..5 and repository-side SR-6 tooling are integrated. The public Consumer SDK is `io.github.daniele21.localllm:consumer-android:0.1.0-alpha.11`, including successful unauthenticated downstream consumption from the public Maven repository.

ADR 0018 owns Consumer trust. The public Harnex service is explicitly bindable without a custom bind permission, while authorization remains fail-closed through Binder UID -> exact installed package -> current signer -> Harnex Control Plane authorization -> enabled use case. Known external consumers appear as `PENDING`; signer replacement becomes `SIGNATURE_CHANGED`; both require explicit user authorization. SDK `disconnect()` supports reversible disconnect/reconnect without weakening Harnex authority.

### Cross-repository RedactGuard evidence

RedactGuard consumes Consumer SDK `0.1.0-alpha.11` and exposes Connect / Disconnect / Retry while leaving authorization Harnex-owned.

Automated evidence is green for the distinct-signer Consumer-first authorization/reconnect/replacement-signer path and the complete Two-APK lifecycle/fault matrix. Focused physical Play Internal evidence also proves the real pre-release RedactGuard-first -> Harnex-later -> `PENDING` -> authorize -> Connect / Disconnect / Reconnect journey with production Consumer SDK/Binder/local inference.

That Play run observed the **same Play App Signing SHA-256 digest for Harnex and RedactGuard**. It is valid pre-release first-party evidence but is not distinct-signer physical proof. Distinct-signer authorization remains covered deterministically.

### Consumer API, OMBRA, evaluation and audit

CA-0..4 are integrated; concrete model/runtime/residency authority stays in Harnex. OMB-6B identity approval, OMB-8 measured quality execution and physical evidence remain open. Model-evaluation work is integrated through EVAL-D-09. Local inference Activity/audit is integrated under ADR 0017 with bounded encrypted app-private history and verified Binder caller attribution; normal telemetry stays content-free.

## Release evidence state

### GitHub release productization

The active [`github-release-productization.md`](workstreams/github-release-productization.md) workstream targets `v0.5.0-rc.1` as the first public GitHub prerelease. ADR 0020 makes GitHub Releases the primary direct Host channel and Play an optional independent channel. Both use the same Host package/service contract; the GitHub APK uses a dedicated signing lineage.

Release preparation is exact-artifact-based: prepare signs one APK and records manifest/checksums/provenance; applicable REAL_ENVIRONMENT evidence must exercise that exact APK; publish verifies and releases the same bytes. `v0.5.0-rc.1` is **not published**.

### Repository protection state

The active `Harnex protected branches` ruleset covers `dev` and `main`, blocks deletion/non-fast-forward updates, requires PRs, requires `Repository validation` and requires branches to be current with their targets.

Two live settings still differ from [`../BRANCHING.md`](../BRANCHING.md): resolved review conversations are not required, and `main` lacks the documented additional approval. This partial configuration mismatch must be reconciled before first public release readiness is claimed.

### Public-release signer obligation

The first public GitHub Host release introduces its dedicated Host signer. The intended public cross-app surface therefore requires claim-matched physical evidence using the exact GitHub-signed Host candidate and a genuinely distinct external Consumer signer. The earlier same-signer Play run cannot satisfy that proof.

### Representative Android runtime evidence

LAS-07 and remaining CRV/SR/Q35/resource claims require representative physical Android evidence with the exact candidate, production JNI/`llama.cpp` path and compatible GGUF where applicable. The 4B 4-bit tier remains part of this evidence gap. Emulator evidence is not physical evidence, and the first GitHub APK must be qualified as the exact prepared bytes rather than a rebuilt or differently signed substitute.

OMB-6B/OMB-8, model evaluation, Q35 device tuning, RAM/thermal evidence and LLUP continue independently where ownership does not conflict.

## Parallel consumer integration quality lane

The active [consumer integration resilience workstream](workstreams/consumer-integration-resilience.md) coordinates simpler, safer external-app onboarding against the existing published SDK. It is separate from RA-4/RA-5 runtime hardening and from open PR #562's Host-owned generic-text onboarding. No unvalidated convenience API or implicit automatic retry is assumed; release/physical obligations remain unchanged.

## Immediate next block

1. integrate release productization into current `dev` with FULL automated validation;
2. reconcile the remaining ruleset mismatch and complete the protected GitHub Release signing environment/key custody plus native release immutability;
3. reconcile `0.5.0-rc.1` scope, run RELEASE/FULL against live `main`, and promote the exact candidate;
4. prepare the exact GitHub-signed APK and run claim-matched physical ARM64/JNI/GGUF and distinct-Consumer signer evidence;
5. publish the same candidate only after blocking evidence closes; Play remains optional unless a Play-specific claim is made;
6. continue independent model/runtime/resource/evaluation work without broadening evidence claims.

## Source links

- Product strategy: [`product.md`](product.md)
- Release productization: [`workstreams/github-release-productization.md`](workstreams/github-release-productization.md)
- GitHub release runbook: [`github-releases.md`](github-releases.md)
- Versioning/distribution: [`versioning.md`](versioning.md)
- Consumer SDK: [`shared-runtime/consumer-android-sdk.md`](shared-runtime/consumer-android-sdk.md)
- Shared runtime: [`shared-runtime/roadmap.md`](shared-runtime/roadmap.md)
- Independent consumer authorization: [`adr/0018-independently-signed-consumer-authorization.md`](adr/0018-independently-signed-consumer-authorization.md)
- Distribution decision: [`adr/0020-official-github-and-play-distribution.md`](adr/0020-official-github-and-play-distribution.md)
- Local inference audit: [`features/local-inference-activity-audit.md`](features/local-inference-activity-audit.md)
- Consumer API / OMBRA: [`shared-runtime/consumer-api/roadmap.md`](shared-runtime/consumer-api/roadmap.md)
- Model evaluation: [`model-evaluation/README.md`](model-evaluation/README.md)
- Qwen3.5: [`qwen35/README.md`](qwen35/README.md)
- LLUP: [`workstreams/llama-cpp-v0-3-residency-qualification.md`](workstreams/llama-cpp-v0-3-residency-qualification.md)
