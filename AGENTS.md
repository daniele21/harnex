# Harnex — Coding Agent Guide

Harnex is the Android local-AI harness/control plane. Consumer apps own product workflows; Harnex owns governed model/runtime policy, Binder sharing and inference lifecycle. Keep inference local: no silent cloud fallback or content logging.

## Durable invariants

- Durable product mission/users/outcomes/principles live in `docs/product.md`; architecture/features implement them without duplicating product truth.
- Model/runtime/Binder state has one canonical owner; UI/adapters translate rather than duplicate policy.
- Android caller identity + Harnex authorization is the shared-runtime authority; caller-declared identity is not.
- JNI handles, jobs, models, processes and evidence are bounded, cancellable and cleaned on every exit path.
- Public Binder/Consumer changes require direct-consumer compatibility evidence.
- Emulator proof never implies ARM64 JNI/llama.cpp, real GGUF, physical memory/thermal or OEM behavior.
- Build/package identity and immutable successful-artifact semantics remain truthful.

## Ownership

| Change | Owner / proof |
| --- | --- |
| Product mission/users/outcomes/principles | `docs/product.md`, `.engineering/product.json` |
| Public/runtime contracts | `core/contracts`, `core/backend-spi`, `core/runtime-core`; adapters/fakes/tests |
| Model/lifecycle truth | `models/model-store`, control-plane stores; runtime/control-plane tests |
| Binder/client/Host | `transports/android-binder-*`, `integrations/android-service-host`, `apps/shared-runtime-client-consumer-fixture`; consumer/two-APK evidence |
| Native execution | `backends/llama-cpp`, `third_party/llama.cpp`; JNI/native/package gates |
| Product experience | phone/console surfaces + `design/*`; design-system/journey evidence |

Follow applicable scoped `AGENTS.md`; extend the canonical owner before adding parallel state.

## Read by task

| Task | Read now |
| --- | --- |
| Docs/copy | affected owner; `docs/README.md` if routing is unclear |
| Product capability/behavior/strategy shaping | `.engineering/product.json`, `docs/product.md`, `skills/shape-product-change/SKILL.md` |
| Behavior/bug/contract | `.engineering/commands.json`, `skills/structured-change/SKILL.md`, `skills/validate-change/SKILL.md` |
| Material UI | above + `skills/design-product-experience/SKILL.md`, relevant `design/*` |
| Integration/release | `skills/preflight-change/SKILL.md`, `.engineering/commands.json`, `.engineering/e2e.json` |
| Missing deterministic remote gate | `skills/remote-preflight/SKILL.md` |
| Persistent work | `skills/plan-workstream/SKILL.md` + active plan; repository state in `docs/current-state.md`; finalize via `skills/finalize-workstream/SKILL.md` |

## Delivery boundaries

Product depth, delivery stage and validation depth are independent.

- `PRODUCT_NONE/LOCAL`: no broad product ceremony; preserve settled intent and prove the local outcome.
- `PRODUCT_FEATURE/STRATEGIC`: establish user/problem/outcome, material value/usability/feasibility/viability risks, assumptions, non-goals and success before substantial implementation. Discovery may narrow, change or reject the requested solution.
- `ITERATION`: owner-local falsification; no publication ceremony after each edit.
- `INTEGRATION`: coherent outcome ready for `dev`; current docs, exact candidate/base, required automated gates and affected E2E. Material UI/UX journeys use `FULL_MEDIA`; residual physical proof is `DEFERRED_TO_RELEASE`.
- `RELEASE`: `FULL` release evidence plus applicable blocking real-environment confirmation.

`SHIPPED` proves delivery, not product impact. Resolve risk dimensions into required gates; stacked publication is exception-only. Define post-release learning only when real use must answer something material; telemetry is not mandatory.

## Context, diagnosis and completion

`.engineering/documentation-policy.json` owns bounded context routes; use `--route product` for material shaping and `--route bug` for implementation. Routes never authorize omitting relevant owners/source.

Missing local tooling is `REMOTE_AUTOMATED`, not user-run work. Reuse only provably equivalent trusted evidence. On failure classify before patching; after two failed repairs with the same signature, change diagnostic strategy and gather discriminating evidence.

Before integration update affected canonical docs. Transfer durable truth and deferred release obligations before deleting completed plans. Never suppress legitimate tests, hide failed/pending gates, leak sensitive content or downgrade evidence merely to obtain PASS.
