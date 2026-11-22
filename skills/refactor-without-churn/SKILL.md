---
name: refactor-without-churn
description: Simplify an existing Harnex owner with behavior-preserving tests; decide when to keep, extract, move or defer rather than split large files mechanically.
---

# Refactor Without Churn

Use only when an existing component exhibits evidenced responsibility confusion, duplicated invariants, excessive change surface, fragile tests or a real lifecycle defect. **File length, method counts and broad linter suppression alone are discovery signals, not authorization to rewrite.**

1. **Capture baseline:** exact source SHA, bounded call path, owner `AGENTS.md`, closest tests and existing SIMP/EMB/UI workstream. State one observable pain and a counterexample (duplicated finalization, recurring bug, impossible fake, unrelated edits required). Avoid a new diary or architecture audit ledger.
2. **Map state and exits first:** who owns model residency, sessions, queue, terminal-once delivery, cancellation, JNI handles, model files, Android lifecycle, Room transactions and privacy-sensitive content? Mark success, error, close, timeout, restart and cancellation paths; preserve resource cleanup order.
3. **Compare options:** **KEEP** when behavior is cohesive; **EXTRACT** only a stable existing domain responsibility with tests; **MOVE** only where canonical ownership is wrong; **DEFER** speculative optimization pending measurements. Prefer private helper/composition to a new module or public abstraction.
4. **Slice minimally:** add a regression that would fail for the observed problem; change one owner/behavior boundary per PR. Keep public/serialized contracts, model/configuration identity, threading, native code semantics and consumer API stable unless explicitly approved with migration proof.
5. **Prove negative paths:** cancellation while queued/active, one terminal, deferred release failure (`DEGRADED` must not become `READY`), idempotent close, rejected/unavailable model, failed audit/persistence and external consumer compile as applicable. For JNI prefer narrow helper and host-native test before touching bridge protocol; never include another `.cpp` as source.
6. **Stop if overhead exceeds value:** compare testability, dependency edges, number of touched owners and specific prior bug after the change. Do not claim quality from fewer LOC, duplicate wrappers or additional layers. Follow `validate-change` and `preflight-change` for risk-based gates.

### Decision record

In the issue/PR (not a second permanent document), state **evidence -> KEEP / EXTRACT / MOVE / DEFER -> unchanged invariants -> focused tests + exact SHA -> remaining physical/SDK proof**. Route Harnex runtime/JNI changes to `docs/workstreams/simplicity-first-runtime.md`, embedded publication to EMB, and LocalMaind chat UI/state changes to its **own repository**.
