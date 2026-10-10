# Active workstreams

Status: active
Document type: documentation-governance
Owner: repository
Canonical scope: documentation.workstreams
Read when: creating, locating, updating or finalizing a bounded implementation workstream
Last reviewed: 2026-09-09

This directory is the canonical home for repository-level **temporary implementation workstreams** that must preserve dependency, sequencing or handoff state across pull requests or coding agents.

A workstream belongs here only while it is active. It is not a second project-status ledger and it must not duplicate durable feature, architecture, ADR, runbook or release information.

## Active bounded work

- [`github-release-productization.md`](github-release-productization.md) — coordinates the first immutable GitHub APK release path, dedicated signing identity, dual GitHub/optional-Play distribution setup and exact-candidate publication gates.
- [`q35-runtime-qualification-wave.md`](q35-runtime-qualification-wave.md) — coordinates the temporary Qwen3.5 CPU runtime qualification wave, including measured-profile acceptance, lifecycle/memory evidence, representative-device gates and final review dependencies.
- [`application-control-plane-ux.md`](application-control-plane-ux.md) — coordinates the Applications -> assigned use case -> preset-control UX implementation, including parallel UI/control-plane slices, revision-safe mutations, adaptive/accessibility convergence and effective two-APK evidence.
- [`control-plane-state-reconciliation.md`](control-plane-state-reconciliation.md) — coordinates startup reconciliation of persisted mandatory built-in control-plane state, conservative upgrade repair, cross-surface consistency and the dependent physical upgrade/two-APK gates.
- [`background-process-lifecycle-hardening.md`](background-process-lifecycle-hardening.md) — coordinates detached logical inference jobs, Binder reconnect/reconciliation, started/background host execution and process-recovery evidence without coupling UI/transport lifetime to runtime/model lifetime.
- [`consumer-integration-resilience.md`](consumer-integration-resilience.md) — reduces and hardens external Android app integration using existing SDK/Host ownership, typed failure guidance, golden-path proof and independent-consumer tests.

## Lifecycle

1. Create a bounded workstream only when the work cannot be represented safely by the repository current-state ledger plus a durable owner.
2. Link the workstream from [`../current-state.md`](../current-state.md) or the closest durable domain owner when it is actively relevant.
3. Keep implementation sequencing, dependencies, acceptance gates and unresolved blockers here; move durable behavior/decisions to the owning feature, architecture, ADR, runbook or release document.
4. When the workstream is complete, transfer durable knowledge, remove temporary status references and **delete the workstream by default**.
5. Archive only when the completed material has independent audit, regulatory, release-evidence or historical value beyond Git history.

## Existing legacy plans

Some active Harness sources predate repo-template-sw 0.4 and still use `*-plan.md`, `*-progress.md` or domain-local `workstreams/` paths. They remain valid owners until intentionally consolidated; do not create new legacy-style plan/progress pairs. Any touched legacy workstream should converge toward this lifecycle rather than adding another status document.

Git history owns normal implementation history.
