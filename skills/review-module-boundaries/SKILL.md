---
name: review-module-boundaries
description: Review Harnex module ownership and dependency fitness before changing Gradle edges, public SDK/contracts, or cross-layer implementation.
---

# Review Module Boundaries

Use when creating/removing a module, changing `api(project(...))` / `implementation(project(...))`, moving source across modules, changing public Binder/embedded contracts, or diagnosing architecture drift. **Do not invoke for a local edit that leaves boundaries untouched.**

1. **Pin scope:** identify exact branch/SHA, touched modules, change type and direct consumers. Read root/closest scoped `AGENTS.md`, `.engineering/baseline.json`, `docs/architecture.md` and just the relevant source/tests. Use `docs/current-state.md` only for integration/release context.
2. **Find canonical owner:** classify the behavior as application workflow, public/wire contract, runtime policy, model selection/store, backend execution/JNI, persistence, transport or presentation. A model handle, request terminal, authorization decision or conversation history must have **one** owner.
3. **Trace real dependencies:** inspect `settings.gradle.kts`, touched `build.gradle.kts`, exported `api` vs `implementation` edges, imported Kotlin/Android/native symbols and callers/fakes. Check same-family edges and cycles as well as namespace direction. Existing `scripts/verify_architecture.py` only checks selected **direct** Gradle edges and native `.cpp` includes: a PASS is not universal cleanliness.
4. **Challenge each new edge:** could an inward contract or existing adapter express it without depending outward? Is a new module justified by a present independent owner, platform isolation, reuse or separate validation/publication? If not, **KEEP** the existing module. Avoid generic `util`, re-exporting concrete implementations or moving domain policy to composition/UI.
5. **Protect deployment and privacy:** `core/runtime-core` remains backend/UI/Host independent; embedded is the approved in-process composition root; Binder Host authorization uses OS caller identity, not request labels; LocalMaind owns chat history and does not depend on Harnex source modules; native pointers and content logs do not cross boundaries.
6. **Demand proof:** write one minimal failing architecture regression where a violation is confirmed; add allowed-edge fixtures so a guard does not ban legitimate dependencies. Public SDK/wire changes require existing external Maven consumers and compatibility tests. Native/lifecycle changes require focused concurrency/cleanup tests plus risk-selected Android evidence.

### Output, short and actionable

Report **observed edge(s) + source reference**, **canonical owner**, **KEEP / REWIRE / EXTRACT / DEFER**, **smallest compatible change**, and **tests required / unverified risks**. Distinguish confirmed violations from hypotheses. Continue with `structured-change` and `validate-change`; at integration use `preflight-change`. Do not update `repo-template-sw` baseline or introduce new architecture diagrams as a substitute for code evidence.
