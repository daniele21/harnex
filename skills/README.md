# Core project-local Skills

These Skills are copied into Harnex and versioned with the project. They encode recurring procedures that should not inflate the root `AGENTS.md`.

Core set:

- `plan-workstream` — create a bounded dependency-aware active plan only when coordination is justified; material product work carries compact shaped intent in that same plan;
- `shape-product-change` — classify `PRODUCT_NONE|LOCAL|FEATURE|STRATEGIC`, establish user/problem/outcome, material product risks/assumptions, smallest sufficient solution and success before substantial product work;
- `structured-change` — preserve ownership, simplicity, resource/failure/data invariants and resolve material ambiguity during meaningful changes;
- `design-product-experience` — reason through meaningful UX/UI work in the correct order, with proportional depth, before implementation/polish;
- `validate-change` — choose the narrowest sufficient validation while iterating and route unavailable deterministic gates to remote automation rather than human execution;
- `preflight-change` — establish exact-head integration/release readiness after base/diff review and risk/executor selection;
- `remote-preflight` — close repository-owned deterministic remote validation when the current agent lacks equivalent Android execution capability;
- `finalize-workstream` — transfer durable knowledge and delete completed plans by default;
- `review-reference-quality` — perform an L0/L1/L2 gap review before important milestones.

Project-local, non-template extensions (do not duplicate the core skills):

- `review-module-boundaries` — inspect the actual owner, direct and same-family edges, API exposure and external consumers before structural changes;
- `refactor-without-churn` — choose KEEP/EXTRACT/MOVE/DEFER using observed problems, lifecycle invariants and focused regression tests.

Harnex may specialize local copies. Record customization in `.engineering/baseline.json` so future migrations merge rather than overwrite local procedure.

`shape-product-change` runs only when product impact justifies it. `PRODUCT_NONE/LOCAL` must stay cheap. `design-product-experience` remains the UX/UI handoff when material user-interface semantics are affected.

Before publication, `preflight-change` owns profile/executor/readiness selection. Automatable Gradle/R8/Lint/build work must not fall back to the user merely because the current agent lacks Android tooling.

Do not create a Skill for one-off instructions. A Skill is justified when a procedure recurs, is conditional, has non-obvious ordering/hazards, or saves substantial repeated agent context.
