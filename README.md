<p align="center">
  <img src="docs/assets/brand/master/harnex-lockup-light.png" width="680" alt="Harnex — Your local AI harness for Android.">
</p>

<h1 align="center">Harnex</h1>

<p align="center">
  <strong>Android local-AI control plane and shared runtime.</strong><br>
  Run local GGUF models once, then expose governed on-device inference to Android apps through one controlled boundary.
</p>

<p align="center">
  <a href="https://github.com/daniele21/harnex/actions/workflows/validate.yml"><img alt="Repository validation" src="https://github.com/daniele21/harnex/actions/workflows/validate.yml/badge.svg"></a>
  <img alt="Android API 26+" src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Consumer SDK" src="https://img.shields.io/badge/Consumer%20SDK-0.1.0--alpha.11-7F52FF">
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
</p>

<p align="center">
  <a href="#quick-start">Quick start</a> ·
  <a href="#use-harnex-from-another-android-app">Consumer SDK</a> ·
  <a href="#architecture">Architecture</a> ·
  <a href="#engineering-proof">Proof</a> ·
  <a href="docs/README.md">Docs</a> ·
  <a href="CONTRIBUTING.md">Contributing</a> ·
  <a href="https://github.com/daniele21/harnex/discussions">Discussions</a>
</p>

---

Harnex lets Android applications use local LLM inference **without embedding a separate model store, JNI layer and runtime lifecycle into every app**.

Consumer apps keep ownership of their product workflow and data. Harnex owns the shared local-AI infrastructure: model resolution, authorization, runtime policy, model residency, sessions, generation, cancellation, audit and runtime evidence.

`llama.cpp` is the current execution backend. **It is not the architecture.** Runtime core talks to a backend-neutral SPI so Android policy and lifecycle stay independent from the concrete inference engine.

## Why Harnex

| Concern | Native stack inside every app | Harnex |
| --- | --- | --- |
| GGUF install and verification | Reimplemented per app | Host-owned model store |
| JNI / native runtime | Coupled to each product | Shared backend boundary |
| Model residency and switching | App-specific lifecycle | Explicit runtime ownership |
| Cross-app access | Ad hoc app contracts | Versioned SDK + Binder |
| Authorization | Caller/application logic | Binder UID → package → signer → Harnex policy → use case |
| Cancellation and recovery | Usually incidental | First-class lifecycle |
| Runtime evidence | Fragmented | TTFT, throughput, memory, thermal, health and evaluation |
| Sensitive inference history | Easy to mix with logs | Separate bounded encrypted local Activity domain |

Harnex is designed for the cases where **privacy, shared local compute and operational control matter as much as raw inference**.

## See it

<table>
  <tr>
    <td align="center"><strong>Overview</strong></td>
    <td align="center"><strong>Playground</strong></td>
    <td align="center"><strong>Models</strong></td>
  </tr>
  <tr>
    <td><img src="docs/assets/readme/harness-overview.png" alt="Harnex Overview"></td>
    <td><img src="docs/assets/readme/harness-playground.png" alt="Harnex Playground"></td>
    <td><img src="docs/assets/readme/harness-models.png" alt="Harnex Models"></td>
  </tr>
</table>

## Quick start

### Prerequisites

- JDK 17
- Android SDK API 36 and Build Tools 36.0.0
- Android NDK 28.2.13676358
- an existing Android emulator or physical device

### 1. Clone and run Harnex

```bash
git clone --recurse-submodules https://github.com/daniele21/harnex.git
cd harnex
bash bootstrap-wrapper.sh
bash scripts/run-emulator-debug.sh --app phone-test
```

The runner installs and launches Harnex; it does not create or boot an emulator for you.

### 2. Install a model

Open **Models** and download a supported catalog model or use an explicitly supported import path. Harnex keeps model identity, integrity and installation separate from selection and runtime residency.

### 3. Run local inference

Open **Playground**, run a prompt and inspect the resulting lifecycle and metrics. **Activity** contains the bounded local inference record; normal telemetry and logs remain content-free.

For full environment, signing and device instructions, see [`docs/android-build-and-run.md`](docs/android-build-and-run.md).

## Use Harnex from another Android app

External apps consume one Maven coordinate:

```kotlin
dependencies {
    implementation("io.github.daniele21.localllm:consumer-android:0.1.0-alpha.11")
}
```

The Consumer SDK owns the public Android client contract and Binder transport. It does **not** give an application access by itself: Harnex derives the caller from Android Binder identity and applies the configured package/signer/use-case authorization policy.

Consumer lifecycle is explicit:

```text
consumer app
   │
   ├─ connect()      attach transport / negotiate / authorize
   ├─ prepare()      resolve the exact assigned execution capability
   ├─ generate()     submit local inference
   ├─ disconnect()   reversible transport detach
   └─ close()        terminal client shutdown
```

**Try the complete external-app flow:** [`samples/hello-harnex`](samples/hello-harnex/README.md) is a runnable standalone Android app that shows its signer identity, walks through Harnex authorization and runs one real local inference using only the published Consumer SDK.

For the full public contract, durable logical jobs and publication guarantees, start with [`docs/shared-runtime/consumer-android-sdk.md`](docs/shared-runtime/consumer-android-sdk.md).

`samples/external-consumer-android` remains the separate Maven-consumption/API-ABI compatibility fixture; it is not used as the end-user demo.

## Architecture

```text
Consumer Android app
        │
        ▼
Consumer Android SDK
        │
        ▼
Binder IPC
        │
        ▼
┌─────────────────────────────────────────────┐
│                   HARNEX                    │
│                                             │
│  Control Plane                              │
│  • Android caller identity / authorization  │
│  • application + use-case policy            │
│  • model resolution / capability state      │
│                                             │
│  Runtime Orchestration                      │
│  • model residency                          │
│  • session + generation lifecycle           │
│  • scheduling / cancellation / cleanup      │
│                                             │
│  Evidence                                   │
│  • telemetry / health / evaluation          │
│  • encrypted local inference Activity       │
└──────────────────────┬──────────────────────┘
                       │
                       ▼
               backend-neutral SPI
                       │
                       ▼
               llama.cpp adapter
                 / JNI / C++
                       │
                       ▼
                 local GGUF
```

### Ownership rules

- **Apps own product workflow and application data.**
- **SDK/Binder owns the external contract and transport.**
- **Harnex owns authorization, model/runtime policy, residency and lifecycle.**
- **Backends own execution, not product/runtime policy.**
- **Native handles never escape the backend boundary.**
- **Downloaded, installed, selected and resident are different model states.**
- **No undeclared model substitution.**
- **Emulator evidence is never presented as physical-device evidence.**

Deep dive: [`docs/architecture.md`](docs/architecture.md) and [`docs/adr/README.md`](docs/adr/README.md).

## Engineering proof

Harnex treats architecture claims as things that should be testable, not just described in diagrams.

| Boundary | Evidence strategy |
| --- | --- |
| Repository architecture | executable layering and repository guards |
| Consumer SDK | external Maven-consumption and API/ABI compatibility validation, including the runnable sample build |
| Binder contracts | Android parceling plus cross-application lifecycle tests |
| Authorization | OS-derived caller identity, signer-aware fail-closed policy and signer-replacement coverage |
| Runtime lifecycle | deterministic load/session/generation/cancellation/recovery tests |
| Native backend | pinned `llama.cpp`, JNI/native/package validation |
| Product UI | Compose semantics, screenshot/media evidence and accessibility/adaptive checks |
| Physical claims | explicit representative-device evidence; never inferred from emulator CI |

The repository distinguishes **automated integration evidence** from **real-environment release evidence**. See [`.engineering/e2e.json`](.engineering/e2e.json), [`docs/device-e2e-testing.md`](docs/device-e2e-testing.md) and [`docs/definition-of-done.md`](docs/definition-of-done.md).

## What Harnex can do today

- install and verify curated local GGUF models;
- run local generation, streaming and cooperative cancellation;
- expose one shared runtime to authorized Android consumer applications;
- bind applications and use cases to explicit model/runtime policy;
- keep inference access tied to Android package/signing identity;
- inspect model/runtime state, request timelines, health and diagnostics;
- collect latency, TTFT, throughput, memory and thermal evidence;
- keep sensitive inference Activity separate from normal telemetry and logs;
- evaluate runtime/model behavior without silently promoting unsupported claims.

The connected Android product currently includes **Overview, Playground, Activity, Applications, Performance, Models, Diagnostics and Settings**.

## Project status

Harnex is an **active pre-stable engineering project**. The architecture and main Android control-plane/runtime paths are implemented, but the project deliberately does not claim universal device or production readiness before the corresponding physical evidence exists.

Current integration truth, active blockers and exact release state live in [`docs/current-state.md`](docs/current-state.md). Capability direction lives in [`docs/roadmap.md`](docs/roadmap.md).

## Documentation

| I want to… | Start here |
| --- | --- |
| Run Harnex locally | [`docs/android-build-and-run.md`](docs/android-build-and-run.md) |
| Try Harnex from another Android app | [`samples/hello-harnex`](samples/hello-harnex/README.md) |
| Integrate an Android consumer app | [`docs/shared-runtime/integrating-consumer.md`](docs/shared-runtime/integrating-consumer.md) |
| Understand the system architecture | [`docs/architecture.md`](docs/architecture.md) |
| Understand trust/security decisions | [`docs/adr/README.md`](docs/adr/README.md), [`SECURITY.md`](SECURITY.md) |
| Work with models and Qwen3.5 | [`docs/qwen35/README.md`](docs/qwen35/README.md) |
| Run physical-device evidence | [`docs/device-e2e-testing.md`](docs/device-e2e-testing.md) |
| See current state | [`docs/current-state.md`](docs/current-state.md) |
| See the roadmap | [`docs/roadmap.md`](docs/roadmap.md) |
| Navigate all documentation | [`docs/README.md`](docs/README.md) |

## Repository map

| Area | Main paths |
| --- | --- |
| Public/runtime contracts | `core/contracts`, `core/backend-spi`, `core/runtime-core` |
| Model lifecycle | `models/model-store`, `models/model-profile`, `models/model-catalog`, `models/model-download`, `models/model-install` |
| Native execution | `backends/llama-cpp`, `third_party/llama.cpp` |
| Android transport | `transports/android-binder-*`, `integrations/android-service-host` |
| Embedded transport | `transports/in-process` |
| Observability | `observability/in-memory-store`, `observability/room-store`, `observability/health-engine`, `observability/android-resource-probe`, `observability/benchmark-engine` |
| Product surfaces | `apps/local-llm-phone-test`, `apps/local-llm-console`, `ui/design-system` |
| Runnable external-app sample | `samples/hello-harnex` |
| External consumption fixture | `samples/external-consumer-android` |

`settings.gradle.kts` remains the authoritative Gradle module list.

## Contributing

Contributions that improve correctness, Android integration, model/runtime evidence, developer experience or documentation are welcome. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md) and use [GitHub Discussions](https://github.com/daniele21/harnex/discussions) for design questions or broader ideas.

The repository uses `dev` as the integration branch and `main` as the stable/release line. Changes are validated according to risk; physical-device evidence is required only for claims that actually depend on physical Android hardware.

## License

MIT. See [`LICENSE`](LICENSE).

Harnex is built by [Daniele Moltisanti](https://daniele21.github.io/) as part of a broader effort to determine the Local / Hybrid / Cloud boundary with evidence rather than ideology.
