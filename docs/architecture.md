# Architecture

Status: active
Document type: architecture
Owner: repository
Canonical scope: architecture.repository
Read when: changing module boundaries, dependency direction, deployment shape or ownership
Last reviewed: 2026-09-05

![Detailed Android Local LLM Harness architecture showing the product control plane, embedded and shared data planes, runtime and model boundaries, and observability](assets/architecture.png)

## Data plane

```text
Native app / Capacitor plugin
            |
       LocalLlmClient
            |
     InProcessTransport
            |
    RuntimeOrchestrator
      |       |       |
 App registry |   TelemetryRepository
      |       |      /              \
 Model profile|  in-memory       Room store
      |       |
      |    Model store
      |       |
      |  verified StoredModel
      |       |
      |  BackendModelSource
      |       |
      +--> Backend SPI
              |
       llama.cpp adapter
              |
          JNI / C++ ---> GGUF
```

The embedded/in-process path and the shared Binder Host execute the same runtime data plane. Transport and composition ownership differ, while model/runtime policy stays Harnex-owned. Shared-runtime deployment and ownership are specified in [`shared-runtime/README.md`](shared-runtime/README.md).

Runtime orchestration depends on `core:backend-spi` for backend-neutral execution operations and on `observability/contracts` for telemetry and inference-audit contracts. It does not depend on a concrete inference backend or Android persistence implementation. Android Room remains isolated in `observability/room-store`; deterministic tests and ephemeral integrations may use `observability/in-memory-store` instead.

The Harness Host Control Plane follows the same isolation rule: platform-neutral application/use-case/preset/binding contracts live in `models/model-profile`, while durable Android persistence lives in `models/control-plane-room-store`. Runtime/model-loading code consumes resolved control-plane identity through contracts and does not depend directly on Room.

The backend SPI is store-neutral. Runtime/model-store code resolves and verifies an installed artifact, then adapts it to `BackendModelSource` immediately before loading. Backend implementations do not own installation, integrity policy or model selection. This dependency boundary is recorded in [ADR 0014](adr/0014-backend-spi-boundary.md).

## Product support envelope

The product exposes only repository-reviewed Qwen3.5 dense 0.8B and 2B artifacts under [ADR 0011](adr/0011-qwen35-only-product-support.md). Public lifecycle, binding, telemetry and backend contracts remain model-family neutral; this is an architectural boundary, not a generic model-support promise.

Users do not extend the product with arbitrary GGUF files. Verified download/install from the curated catalog is the only consumer model acquisition path. Generic manual-import, legacy-model and multi-family product cases are removed rather than represented through compatibility states. The focused ownership map lives in [`qwen35/architecture.md`](qwen35/architecture.md), and Q35-1 cleanup is owned by [`qwen35/workstreams/curated-model-baseline.md`](qwen35/workstreams/curated-model-baseline.md).

## Persistent observability

Generation observability follows the same request identifiers and lifecycle as the data plane:

```text
GenerationRequest
      |
      +-- QUEUED
      +-- RUNNING
      +-- COMPLETED / FAILED / CANCELLED
                    |
             TelemetryRepository
               /          \
      bounded memory     Room database
```

The telemetry schema owns three separate domains:

1. generation run records, keyed by request ID;
2. append-only structured logs with request correlation;
3. latest health result per health-check ID.

Run updates replace the record for the same request ID, while logs remain ordered timeline events. Retention is bounded independently for runs and logs. Room operations execute on a dedicated single-thread executor so the current synchronous inspection API never requires main-thread database access.

Normal telemetry may contain:

- stable application, use-case, request and model identifiers;
- lifecycle status and error codes;
- timestamps and durations;
- queue, model-load, TTFT, prefill and decode timings;
- token counts and decode throughput;
- bounded structured metadata fields.

Normal telemetry must not contain prompts, generated output, arbitrary exception messages or model bytes. Telemetry persistence is best-effort: a database or diagnostic failure must never fail, cancel or corrupt inference.

## Durable inference Activity audit

Inference Activity is a separate persistence domain from normal telemetry. `observability/contracts` owns `InferenceAuditRepository` and the bounded audit model; `observability/in-memory-store` provides deterministic test support; `observability/room-store` owns Android Room persistence plus AES-GCM sensitive-content encryption using an app-scoped Android Keystore key.

`core/runtime-core` owns the canonical audited client boundary. `InferenceAuditLocalLlmClient` wraps accepted generation so durable admission exists before execution, the runtime-private one-shot prompt bridge records the actual rendered effective prompt before decode, and terminal content/metrics commit before normal completion is forwarded. The effective prompt does not transit public generation events, normal Logs or Telemetry.

The shared Host resolves external audit identity from authorized Binder caller state rather than trusting caller-supplied package labels. The phone Playground reaches the same audited client with `HARNEX_INTERNAL` origin. The resulting Activity record carries stable application/use-case/request identity and truthful `COMPLETED`, `FAILED`, `CANCELLED` or `INTERRUPTED` terminal state.

Sensitive input, effective prompt, answer and reasoning remain local to the bounded encrypted audit store. Metadata needed for list/filter/restart reconciliation remains minimal. Startup reconciliation converts orphaned non-terminal audit rows to `INTERRUPTED`; clear-history and retention operate on Activity history without mutating runtime jobs, models, control-plane state or normal telemetry.

`apps/local-llm-phone-test` owns the Activity list/detail/filter product surface. `requestId` correlates Activity with privacy-safe technical Runs/Logs without merging their storage or privacy semantics. The durable feature contract and rationale are [`features/local-inference-activity-audit.md`](features/local-inference-activity-audit.md) and [ADR 0017](adr/0017-durable-local-inference-audit.md).

## Control plane

The `local-llm-console` application is the initial developer control plane. It will expose:

- runtime overview;
- application/use-case bindings;
- installed GGUF artifacts;
- model/context lifecycle;
- per-run timelines;
- structured logs;
- latency, throughput and memory metrics;
- cache inspection and invalidation;
- sanity and health suites;
- benchmark history;
- privacy-safe diagnostic export.

Harness-managed application/use-case configuration has one durable persistence boundary. `models/model-profile` owns the neutral domain and `HostControlPlaneStore` contract; `models/control-plane-room-store` owns the Android Room adapter for registered applications, revisioned use cases, revisioned presets, revisioned bindings and exact preset exposure. Configuration replacement is transactional, and persisted preset execution policy includes the configured cache/residency settings without moving runtime resource ownership into the store.

During the embedded phase, apps will expose a signature-protected diagnostics bridge. In the shared phase, the console will query the central host directly.

A Room database stored in one embedded application's private directory is not directly readable by the separate console application. The Room repository establishes persistence and query semantics now; cross-application viewing remains dependent on the protected diagnostics bridge planned for the integration phase.

## Validation plane

The `apps/device-test-runner` application is an isolated, debuggable validation surface for the embedded data plane. It does not own product behavior and must not introduce an alternative runtime implementation.

Its instrumentation tests use the production implementations of:

```text
FileSystemModelStore
        |
RuntimeOrchestrator
        |
Backend SPI
        |
LlamaCppInferenceBackend
        |
JNI / llama.cpp
```

A host-side `adb` runner may stream an exact developer-selected validation GGUF into the test application's private data directory. This is test infrastructure only; it does not create a consumer model-import path. The model remains outside the repository and APK artifacts.

The validation plane covers behavior that cannot be established by JVM or host-native tests alone:

- Android ABI and JNI packaging;
- GGUF inspection on device;
- app-private test artifact installation and integrity verification;
- native model and context lifecycle;
- streaming generation and metrics;
- cooperative active cancellation;
- repeated lifecycle and process-memory regression checks.

Test configuration may describe a specific curated model and device, but it must preserve the same explicit binding path used by applications. Device tests may measure production code; they must not duplicate or bypass model resolution, store integrity or runtime ownership rules.

## Model identity

A model is represented at three levels:

1. `GgufArtifact`: immutable physical file identified by SHA-256.
2. `GgufModelProfile`: exact load configuration for that artifact.
3. `UseCaseProfile`: prompt, generation, output and cache policy for a product use case.

`AppModelBinding` resolves `applicationId + useCaseId` to a single explicit use-case profile.

Multiple profiles may target different curated Qwen3.5 0.8B/2B artifacts or quantizations. Model-aware identity and scheduling do not make the catalog extensible to other families or tiers.

## Cache hierarchy

Caches are separate domains because they have different invalidation rules:

1. GGUF artifact store on disk.
2. Operating-system file page cache through memory mapping.
3. Loaded model handle in RAM.
4. Context/KV cache per session.
5. Prefix/session snapshots keyed by model, backend build and prompt profile.
6. Optional deterministic result cache, namespaced by application and use case.

The initial implementation only defines artifact and in-memory lifecycle contracts. Snapshot/result caching will be added after correctness and benchmark baselines are established.

## Runtime invariants

- One loaded model and one active decode by default.
- No undeclared model substitution.
- Every generation has stable application, use-case, session and request identifiers.
- Sensitive prompt/output persistence is confined to the bounded encrypted Activity audit store; normal telemetry, structured logs and diagnostics export remain content-free.
- Audit persistence required for accepted inference is strict; audit storage/encryption failure must not silently fall back to unaudited execution or plaintext.
- Telemetry failures are non-fatal to inference.
- Native handles are never exposed outside the backend module.
- Runtime core never selects or imports a concrete backend implementation.
- Backend implementations never own model-store integrity, installation or selection policy.
- Large payloads must not cross Binder inline when the established contract uses bounded/streamed or Host-owned representations.
- Physical backend operations and lifecycle transitions are serialized by `RuntimeOrchestrator`; lifecycle-specific state/handle ownership remains with the explicit owner below.
- Per-session request admission, close intent and release reservation are owned by `SessionLifecycle`; the session descriptor is the single context-handle owner and `RuntimeOrchestrator` serializes physical context creation/release.
- Per-generation cancellation intent and terminal-once delivery are owned by `GenerationLifecycle`; `SingleDecodeScheduler` remains authoritative for queued and active decode state.
- Physical model residency state and the single resident model handle are owned by `ModelResidencyLifecycle`; `ModelStore` owns installation/integrity, memory policy decides admission/eviction demand, and `RuntimeOrchestrator` serializes native load/unload calls around the residency transitions.
- Cancellation and partial failures must leave the runtime recoverable.

## Modularity and maintainability

The codebase must remain modular, extensible and independently testable without becoming fragmented into speculative modules.

Dependencies follow this direction:

```text
apps / integrations / composition roots
        |
     transports
        |
 core contracts + runtime-core
        |              \
        |               +--> model-profile + model-store + observability contracts
        |
 core/backend-spi
        |
 concrete backend implementations
        |
 llama.cpp Kotlin bridge / JNI / C++
```

Control-plane persistence is a one-way Android adapter dependency:

```text
models/control-plane-room-store
        |
models/model-profile
        |
core/contracts
```

Runtime core depends inward on the backend SPI. Concrete backends depend on the SPI and are selected only by composition roots; runtime core does not depend outward on them. Higher layers may depend on lower-level contracts. Lower layers must not import application UI, Capacitor adapters or other product-specific integrations.

The main ownership boundaries are:

```text
core/contracts
    public application/consumer contracts and backend-independent DTOs

core/backend-spi
    internal backend-neutral model-source, capability, prompt, context,
    generation, cancellation and backend-error contracts

core/runtime-core
    orchestration, model verification/adaptation, sessions, scheduling,
    explicit session/generation/model-residency lifecycle ownership,
    recovery policy, telemetry emission and canonical inference-audit client integration

models/model-profile
    model, use-case and application binding configuration plus the
    platform-neutral Host Control Plane store contract

models/control-plane-room-store
    Android Room persistence for Harness applications, revisioned use cases,
    revisioned presets, revisioned bindings and exact preset exposure

models/model-store
    artifact storage, identity and integrity

backends/llama-cpp
    InferenceBackend implementation plus Kotlin/JNI/C++ llama.cpp adaptation

observability/contracts
    stable telemetry, log, health, retention/query and inference-audit contracts

observability/in-memory-store
    bounded ephemeral telemetry/audit implementation and deterministic test doubles

observability/room-store
    Android Room telemetry/audit schemas, audit encryption, persistence, retention
    and database lifecycle

transports
    in-process and Binder communication; no runtime policy ownership

integrations
    thin native Android/Host/Capacitor adapters; Host authorization owns verified
    external caller attribution before runtime/audit composition

apps
    composition roots, Activity/developer surfaces and isolated device validation
```

A new module is justified only when it owns a real responsibility, creates a necessary dependency boundary, provides actual reuse, isolates a platform or third-party dependency, or needs an independent testing/release boundary.

Do not introduce empty modules merely to anticipate the target architecture. Do not solve duplication by moving unrelated code into generic utility packages. Shared abstractions must represent a clear domain concept.

Public APIs must remain small and stable. Backend, transport, model store, scheduler, telemetry store and cache policies should be replaceable behind explicit interfaces when replacement is part of the architecture.

## Shared generation engine

Synchronous generation and streaming must share the same underlying generation behavior:

- prompt and chat-template preparation;
- tokenization;
- context-limit validation;
- sampler construction;
- prefill;
- decode;
- token-to-text conversion;
- stop-condition handling;
- metric collection;
- typed error mapping;
- temporary resource ownership and cleanup.

They differ only in how output is delivered and how cancellation is surfaced to the caller.

The JNI boundary must remain coarse-grained. Avoid crossing JNI once per token when output can be aggregated safely.

Native code should be split by responsibility and linked through CMake. The target decomposition is conceptually:

```text
backend_runtime
model_registry
context_registry
gguf_inspector
tokenizer
sampler_factory
generation_engine
streaming_sink
cancellation_registry
jni_converters
jni_entrypoints
```

This is an ownership map, not a requirement to create empty files. Components should be extracted when the corresponding responsibility has concrete behavior and tests.

Do not include implementation `.cpp` files from other `.cpp` files to share logic.

## Architectural change policy

A change that alters module ownership, dependency direction, public contracts, native resource ownership, model identity, transport boundaries or privacy defaults requires:

1. updated architecture documentation;
2. an ADR when the decision materially constrains future implementations;
3. updated tests and validation evidence;
4. updated coding-agent navigation when paths or ownership change.

The repository-wide completion rules are defined in [`definition-of-done.md`](definition-of-done.md).

## Executable ownership boundaries

`scripts/verify_architecture.py` enforces production Gradle project-dependency direction for infrastructure modules and rejects implementation `.cpp` includes. Apps are composition roots and may depend on concrete implementations; lower-level code must not depend on app/UI modules.

- `core/*` may use neutral model/observability contracts, but not concrete backends, transports or Host integration.
- `transports/*` owns transport and wire adaptation, not model/runtime/persistence policy; `integrations/android-service-host` depends only on the public core and Binder contracts.
- `models/*`, `observability/*` and `evaluation/*` may depend on their reviewed lower-level contracts; none may reach into Host/UI/app modules.
- `backends/*` implements the backend SPI and must not depend on the orchestrator or Binder.

These are conservative, executable ownership guards for direct production project dependencies. They do not establish runtime behavior, transitive dependency cleanliness, or architectural correctness on their own. Expand rules only when a real boundary is established and proved by the owning consumers/tests.
