# Harnex product

Status: active
Document type: target-specification
Owner: repository
Canonical scope: product.strategy
Read when: shaping a material Harnex capability, boundary, target-user or product-quality change
Last reviewed: 2026-11-19

This document owns concise durable product truth for Harnex. It constrains product decisions; architecture, feature behavior, roadmap sequencing and implementation detail keep their existing owners.

## Mission

Make governed on-device AI a reusable Android capability instead of a native inference stack that every application must rebuild, secure, operate and diagnose independently.

## Primary users / consumers

- Android developers and Android applications that need reliable local generative-AI execution without owning GGUF, JNI, runtime lifecycle, model residency and resource governance themselves.
- Harnex operators/developers who configure models, application/use-case policy, runtime health and evidence for those consumers.

## Core problems / jobs

- Give consumer apps one stable, versioned boundary for local inference while keeping their product workflow outside Harnex.
- Keep model resolution, runtime/session lifecycle, scheduling, cancellation, recovery and resource ownership inside Harnex for each deployed runtime, whether embedded in one app or shared by several.
- Govern cross-application access from Android caller identity and explicit Harnex authorization rather than caller-declared identity.
- Make local-AI capability, performance and failure claims observable without leaking sensitive inference content into normal telemetry/logging.

## Value proposition

A consumer app can use local AI as a governed Android capability: **embedded SDK** for one independently installed commercial app, or **Shared Host + Binder Consumer SDK** when multiple apps benefit from a central runtime. In both deployments Harnex owns the difficult inference/model/resource policy, while each consumer owns user-facing workflows and domain behavior. Embedded distribution must not require installing or operating a separate Harnex Host.

## Meaningful differentiation

- **Portable runtime, two deployment modes:** the same runtime data plane can be embedded in a standalone Android app or shared across authorized apps through a separate Host.
- **Shared governed runtime:** the Host mode deduplicates model/runtime ownership across multiple apps when this deployment has real value.
- **Control-plane authority:** application/use-case/model/runtime policy is explicit and separate from the inference backend.
- **Android-native trust boundaries:** Shared Host authorization follows Binder UID → installed package → signer → Harnex use-case policy; embedded execution stays inside the installing app's Android sandbox without requiring Binder authorization.
- **Backend-neutral policy:** `llama.cpp` is an execution backend behind an SPI, not the product architecture.
- **Evidence as a product capability:** runtime health, latency, memory, thermals, evaluation and encrypted local inference Activity make execution inspectable while privacy-safe telemetry remains content-free.

## Core outcomes

- An independent Android app can consume a versioned embedded SDK and execute supported local inference in its own APK without installing a Harnex Host or owning model/runtime internals.
- A compatible shared Android consumer can integrate the Binder Consumer SDK, be explicitly authorized and execute local inference through Harnex without owning model/runtime internals.
- Consumer failure modes such as Host absence, restart, authorization denial, cancellation and signer replacement fail truthfully and recoverably.
- Operators can understand model/runtime state and evidence well enough to configure, diagnose and qualify supported local-AI behavior.
- Model/runtime policy can evolve without coupling public product semantics to one native backend implementation.

## Non-goals

- Harnex is not a general-purpose chatbot or consumer assistant.
- Harnex does not own consumer-app workflows, domain decisions or document/business data models.
- Harnex is not a cloud inference gateway and must not introduce silent cloud fallback.
- Harnex does not promise that every GGUF/model/device combination is supported; the supported product envelope is curated and evidence-bound.
- Harnex does not treat selection, installation and RAM residency as interchangeable model states.

## Product principles

- **Consumer owns workflow; Harnex owns local-AI infrastructure and policy.** Chat history, conversation context selection, application persistence and other consumer-domain behavior stay in the consumer, not the harness.
- **Authority is derived, not declared.** Android identity and Harnex policy govern shared access.
- **No silent substitution.** Model choice, backend behavior, cloud use and fallback must remain explicit and truthful.
- **Lifecycle is product behavior.** Residency, admission, generation, cancellation, cleanup, restart and recovery are first-class supported semantics.
- **Privacy boundaries are durable.** Sensitive inference Activity may be encrypted locally; normal telemetry/logs/exports remain content-free.
- **Evidence strength matches the claim.** Emulator proof is not physical-device/JNI/GGUF/memory/thermal/OEM proof.
- **Prefer bounded deterministic policy over uncontrolled self-tuning.** Runtime optimization must remain explainable, measurable and reversible.

## Product quality attributes

| Attribute | Importance | Product promise / principle | Technical owner |
| --- | --- | --- | --- |
| Privacy / trust | critical | no silent cloud fallback or content logging; Shared Host access is Android-identity authorized and embedded state is confined to the app sandbox | `docs/architecture.md`, `transports/android-binder-*`, embedded composition, observability Activity owners |
| Reliability / recovery | critical | Host/runtime/session failures remain truthful, cancellable and recoverable without orphaned state | `core/runtime-core`, service host, model/runtime lifecycle owners |
| Compatibility | critical | public Consumer/Binder behavior is versioned and compatibility evidence covers direct consumers | `core/contracts`, Consumer SDK/Binder owners, shared-runtime docs |
| Resource governance | critical | model/context/runtime resources are bounded and device claims are evidence-bound | runtime/model residency owners, `docs/memory-management/`, resource observability |
| Developer experience | critical | an app integrates local AI through a small stable boundary without native-model internals; embedded delivery requires one APK, no Host dependency | Consumer Android SDK, embedded SDK, and their focused integration guides |
| Performance / thermals | high | latency, throughput, memory and thermal behavior are measured rather than assumed | backend/runtime + observability/benchmark owners |
| Observability | high | runtime decisions and failures expose useful privacy-safe evidence | `observability/*`, Activity/diagnostics owners |
| Accessibility / product UX | high | Harnex operator surfaces make state, action and recovery understandable | `design/*`, product UI owners |

## Success signals

- **Acceptance:** supported embedded and Shared Host/Consumer contracts, lifecycle/failure semantics and relevant operator journeys pass claim-matched automated and physical release evidence for the exact candidate.
- **Outcome:** an independent embedded app can run inference without a separately installed Host, while a shared Consumer can authorize, execute, recover and diagnose via the Host without duplicating Harnex-owned runtime/model policy. RedactGuard is the current shared reference; the external embedded-app Maven-only build proof is implemented, while physical GGUF inference and release qualification remain outstanding.
- **Product impact:** growth in credible consumer use, lower integration/support friction, stable task success and representative-device quality are stronger signals than feature count. Use privacy-safe evidence; analytics are not required by default.

## Canonical product sources

- Product-development routing: `.engineering/product.json`
- Public identity/value/usage: `README.md`
- Architecture/trust/ownership: `docs/architecture.md`
- Capability direction: `docs/roadmap.md`
- Current integrated/blocked/next truth: `docs/current-state.md`
- Feature behavior: `docs/features/` and focused domain docs
- Consumer boundary: `docs/shared-runtime/consumer-android-sdk.md`
- Product experience: `design/ux-contract.json` and `design/brand-kit.json`
- Delivery/E2E routing: `.engineering/commands.json` and `.engineering/e2e.json`

Update this file only when durable mission, users/consumers, owned problems, value, differentiation, product boundaries/principles, core outcomes or material quality promises change.
