# Harnex documentation

Status: active
Document type: documentation-governance
Owner: repository
Canonical scope: documentation.routing
Read when: locating Harnex documentation ownership, choosing the right guide or changing documentation governance
Last reviewed: 2026-09-07

This is the documentation entry point for users, integrators and contributors. Start from the task you are trying to complete; repository/agent governance is documented later on this page.

## Choose your path

| I want to… | Start here | Then |
| --- | --- | --- |
| Understand durable product mission, users, outcomes and decision boundaries | [`product.md`](product.md) | [`.engineering/product.json`](../.engineering/product.json) and `skills/shape-product-change/SKILL.md` for change routing |
| Run Harnex on Android | [`android-build-and-run.md`](android-build-and-run.md) | [`device-e2e-testing.md`](device-e2e-testing.md) for physical-device validation |
| Try Harnex from another Android app | [`../samples/hello-harnex`](../samples/hello-harnex/README.md) | [`shared-runtime/consumer-android-sdk.md`](shared-runtime/consumer-android-sdk.md) for the complete public contract |
| Integrate Harnex into another Android app | [`shared-runtime/integrating-consumer.md`](shared-runtime/integrating-consumer.md) | [`shared-runtime/consumer-android-sdk.md`](shared-runtime/consumer-android-sdk.md) for the full contract |
| Understand the architecture | [`architecture.md`](architecture.md) | [`adr/README.md`](adr/README.md) for durable decisions |
| Understand model support | [`qwen35/README.md`](qwen35/README.md) | [`curated-model-catalog.md`](curated-model-catalog.md) and model lifecycle docs |
| Understand security/privacy boundaries | [`architecture.md`](architecture.md) | [`../SECURITY.md`](../SECURITY.md), ADRs and Activity/audit docs |
| Work on runtime performance | [`llama-cpp-runtime-optimization-plan.md`](llama-cpp-runtime-optimization-plan.md) | [`benchmark-engine.md`](benchmark-engine.md), [`resource-observability.md`](resource-observability.md) |
| Validate real-device behavior | [`device-e2e-testing.md`](device-e2e-testing.md) | [`device-e2e-evidence.md`](device-e2e-evidence.md) |
| See what is implemented right now | [`current-state.md`](current-state.md) | [`roadmap.md`](roadmap.md) for what comes next |
| Contribute code or docs | [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | [`../AGENTS.md`](../AGENTS.md) for repository engineering invariants |

## Core documentation

### Product, architecture and public boundaries

- [`product.md`](product.md) — durable product mission, primary consumers, owned problems, differentiation, non-goals, product principles, quality promises and success signals.
- [`architecture.md`](architecture.md) — system ownership, runtime/control-plane/backend boundaries.
- [`adr/README.md`](adr/README.md) — accepted architecture decisions.
- [`shared-runtime/README.md`](shared-runtime/README.md) — shared Host/Consumer runtime model.
- [`shared-runtime/integrating-consumer.md`](shared-runtime/integrating-consumer.md) — practical integration steps, connection/setup errors, and lifecycle safety.
- [`shared-runtime/consumer-android-sdk.md`](shared-runtime/consumer-android-sdk.md) — public Android Consumer SDK and Maven contract.
- [`api-usage.md`](api-usage.md) — embedded/local runtime API reference.
- [`features/README.md`](features/README.md) — feature-level specifications.

### Models and generation

- [`qwen35/README.md`](qwen35/README.md) — Qwen3.5 product/runtime support.
- [`curated-model-catalog.md`](curated-model-catalog.md) — reviewed model catalog.
- [`secure-model-download.md`](secure-model-download.md) — download trust and integrity.
- [`model-installation.md`](model-installation.md) — installation lifecycle.
- [`harness-model-inventory-state.md`](harness-model-inventory-state.md) — installed/selected/resident state model.
- [`generation-configuration-and-prompting-plan.md`](generation-configuration-and-prompting-plan.md) — generation configuration and prompt ownership.

### Observability, evidence and evaluation

- [`console-observability.md`](console-observability.md) — observability model.
- [`health-engine.md`](health-engine.md) — health checks.
- [`resource-observability.md`](resource-observability.md) — memory/thermal/resource evidence.
- [`benchmark-engine.md`](benchmark-engine.md) — benchmark identity and comparison.
- [`model-evaluation/README.md`](model-evaluation/README.md) — semantic model evaluation.
- [`device-e2e-testing.md`](device-e2e-testing.md) — physical Android test procedure.
- [`device-e2e-evidence.md`](device-e2e-evidence.md) — evidence format and privacy boundary.

### Android product and UX

- [`features/phone-app-architecture.md`](features/phone-app-architecture.md) — phone app architecture.
- [`harness-ux-ui-implementation-plan.md`](harness-ux-ui-implementation-plan.md) — target product behavior.
- [`harness-ux-ui-implementation-progress.md`](harness-ux-ui-implementation-progress.md) — current UX implementation state.
- [`design-system.md`](design-system.md) — design system.
- [`harnex-brand-guidelines.md`](harnex-brand-guidelines.md) — Harnex brand rules.

### Build, release and repository state

- [`android-build-and-run.md`](android-build-and-run.md) — build and run.
- [`play-internal-phone-test.md`](play-internal-phone-test.md) — Google Play Internal Testing workflow.
- [`definition-of-done.md`](definition-of-done.md) — completion/evidence boundary.
- [`versioning.md`](versioning.md) — version and release identity.
- [`releases/harness-0.5.md`](releases/harness-0.5.md) — active Harness 0.5 release checklist.
- [`current-state.md`](current-state.md) — exact integrated state, blockers and immediate next action.
- [`roadmap.md`](roadmap.md) — capability roadmap.

## Documentation principles

Harnex uses progressive disclosure and single ownership: **one durable fact has one canonical owner**. The root README explains what Harnex is and provides the shortest usable path; focused documents own the detailed contract.

Machine policy is [`.engineering/documentation-policy.json`](../.engineering/documentation-policy.json). [`documentation-policy.json`](documentation-policy.json) is a compatibility mirror kept byte-identical to the `.engineering` owner by Repository health.

### README ownership

Treat the root README as two semantic owners:

- **README identity** — title, summary, target audience/outcome, stable differentiation and mission. Change only when those claims materially change.
- **README usage** — setup, run, public integration and copy-paste examples. Update whenever current instructions would otherwise become incomplete or misleading.

A normal operational change may therefore report `README_IDENTITY: N/A` and `README_USAGE: UPDATED`.

## Canonical ownership

| Question | Canonical source |
| --- | --- |
| What product is Harnex trying to be, for whom and with which durable boundaries? | [`product.md`](product.md) |
| What is Harnex and why should I care? | root README identity sections |
| How do I run/use/integrate Harnex? | root README usage + focused runbooks/API docs |
| Integrated state and blockers | [`current-state.md`](current-state.md) |
| Capability milestones | [`roadmap.md`](roadmap.md) |
| Repository target | [`implementation-plan.md`](implementation-plan.md) |
| Active bounded work | [`workstreams/`](workstreams/README.md) when needed |
| Durable feature behavior | [`features/`](features/README.md) |
| Architecture and decisions | [`architecture.md`](architecture.md), [`adr/`](adr/) |
| E2E environments/fidelity/residual gaps | [`.engineering/e2e.json`](../.engineering/e2e.json) |
| Merge/release completion | [`definition-of-done.md`](definition-of-done.md), [`releases/harness-0.5.md`](releases/harness-0.5.md) |
| Historical exception material | [`archive/`](archive/) |

## Documentation impact contract

Code and durable documentation ship together. During `preflight-change`, classify the affected documentation surfaces from observable behavior, not filenames:

- `README_IDENTITY`;
- `README_USAGE`;
- `FEATURE_DOCS`;
- `ARCHITECTURE`;
- `ADR`;
- `SECURITY_DATA`;
- `OPERATIONS`;
- `PRODUCT_EXPERIENCE`;
- `CURRENT_STATE`.

Use `UPDATED` or `N/A`. Publication readiness requires `DOCS_CURRENT_WITH_IMPLEMENTATION: PASS`.

Update an existing canonical owner when behavior changes. Create a new document only for durable, non-obvious behavior that is not already discoverable from a public contract, test, architecture owner or focused specification. Do not create documentation merely because a PR or task completed.

E2E target/environment/fidelity changes update `.engineering/e2e.json`; logs, screenshots and reports are bounded evidence rather than new durable documentation owners.

## Document lifecycle

Durable types include roadmaps, target/feature specifications, architecture, ADRs, API references, runbooks/evidence, release/completion policy and design/asset owners. `current-state.md` is the single repository operational ledger.

`workstream-state` is temporary. New repository-level workstreams live under [`workstreams/`](workstreams/README.md). Completed workstreams are deleted by default after durable knowledge is transferred; `archive/` is exception-only. Git history owns normal implementation history.

## Required metadata

Every active non-ADR Markdown document under `docs/` contains:

```text
Status: active
Document type: <supported type>
Owner: <repository or domain>
Canonical scope: <unique dotted scope>
Read when: <specific trigger>
Last reviewed: YYYY-MM-DD
```

ADRs keep ADR status/date format. Archived documents use `Status: historical`.

## Reading and writing budgets

CI enforces [`.engineering/documentation-policy.json`](../.engineering/documentation-policy.json): context budgets, reachability, unique canonical scopes, duplicate detection and agent-guide limits. Baselined oversized documents may only shrink.

## Precedence

Executable contracts/tests → accepted ADRs → architecture → focused specifications → target overview → current state → roadmap → README/agent guides → retained history.

When two documents disagree, correct the canonical owner rather than adding another reconciliation layer.

## Validation

```bash
python3 scripts/verify-docs.py --base <target-branch-commit>
python3 scripts/verify_e2e.py
python3 scripts/verify-agent-navigation.py
python3 -m py_compile scripts/*.py
git diff --check
```
