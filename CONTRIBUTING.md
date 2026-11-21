# Contributing to Harnex

Thanks for helping improve Harnex. Contributions are welcome across Android integration, runtime correctness, model support, observability/evidence, developer experience, documentation and tests.

If you are not sure where a change belongs, open a [GitHub Discussion](https://github.com/daniele21/harnex/discussions) before investing in a large implementation.

## Good ways to contribute

- fix a reproducible bug or lifecycle edge case;
- improve Android/Consumer SDK developer experience;
- strengthen tests around public contracts, Binder, runtime or model lifecycle;
- add evidence or diagnostics without weakening privacy boundaries;
- improve documentation, examples or troubleshooting;
- improve performance only with comparable evidence;
- help make physical-device support more reproducible and reviewable.

For substantial new capabilities, prefer an issue/discussion first so ownership and evidence requirements are clear.

## Product-impact routing

Product depth is independent from delivery stage and validation depth. Classify the change by product impact, not file count or coding effort:

- `PRODUCT_NONE` — implementation-only work such as a bug fix, refactor or internal engineering change with no material supported-product change;
- `PRODUCT_LOCAL` — settled small behavior/copy/interaction change; state affected user/consumer, expected outcome and observable acceptance;
- `PRODUCT_FEATURE` — new capability or meaningful supported workflow/behavior change;
- `PRODUCT_STRATEGIC` — material change to target user, product boundary, value proposition, trust model, platform or another broad hard-to-reverse product decision.

For `PRODUCT_FEATURE` or `PRODUCT_STRATEGIC`, read [`.engineering/product.json`](.engineering/product.json), [`docs/product.md`](docs/product.md) and [`skills/shape-product-change/SKILL.md`](skills/shape-product-change/SKILL.md) before substantial implementation. Establish user/problem/outcome, material value/usability/feasibility/viability risks and assumptions, non-goals, quality constraints and success evidence. Discovery may validly narrow the scope, choose an alternative or conclude `DO_NOT_BUILD`.

Keep this reasoning compact in the existing issue/workstream/PR; do not create parallel PRD/progress documents solely for process compliance. Shipping proves delivery, not confirmed product impact.

## Development setup

Prerequisites:

- JDK 17;
- Android SDK API 36;
- Android Build Tools 36.0.0;
- Android NDK 28.2.13676358;
- Python 3;
- Git with submodule support.

Clone and bootstrap:

```bash
git clone --recurse-submodules https://github.com/daniele21/harnex.git
cd harnex
bash bootstrap-wrapper.sh
```

Use the committed Gradle wrapper for local and CI builds.

Optional environment sanity check:

```bash
java -version
python3 --version
cmake --version
./gradlew --version
```

## Branch model

- `dev` — integration branch;
- `main` — stable/release line;
- contributor branches — use a focused name such as `feature/<scope>`, `fix/<scope>` or `docs/<scope>`.

Keep commits scoped and imperative when practical. Avoid mixing unrelated cleanup into a behavioral change.

### Fast coding-agent workflow

Open the implementation PR as a **draft** and keep it draft while modifying code.
GitHub automatically runs cheap repository/metadata checks, but defers the
expensive Android/native validation and Embedded SDK Maven/APK staging.
A green draft `Repository validation` is **iteration-only**, never integration
or release evidence; draft PRs cannot be merged.

During iteration, prefer focused checks for the owning module and direct
consumers. When implementation, relevant tests and docs are coherent, mark
the PR **Ready for review** once: the existing risk selector then runs all
required exact-head integration gates, including native, Maven/APK, and
emulator checks where relevant. An explicit `/preflight auto` can still
request remote exact-head validation when needed. If corrections are
necessary, convert the PR back to draft *before pushing repair commits*;
promote it again after the fixes. Ready PR updates intentionally rerun
required integration checks; `main` releases retain mandatory FULL
validation and applicable physical-device evidence.

This is **stage gating, not test reduction**. Neither local tooling shortages
nor a draft check can substitute for the exact candidate/base evidence
required to merge into `dev` or promote `main`.

Kotlin/Java changes confined to the standalone embedded sample select its Maven
consumer build, unit tests, ARM64 APK packaging checks and API 35 UI smoke.
Find these under the candidate’s Validate run. Mixed changes also validate the
affected Harnex modules. Native, build and unfamiliar sample inputs keep their
conservative validation profile.

## Start with the owning boundary

Harnex intentionally keeps one canonical owner for model/runtime/Binder state. Extend that owner instead of creating parallel policy or state.

Key invariants:

- product/model selection is explicit; no silent model substitution;
- native pointers and `llama.cpp` implementation types stay inside `backends/llama-cpp`;
- runtime core depends on backend-neutral contracts rather than concrete backend policy;
- Binder/public Consumer changes must preserve direct-consumer compatibility;
- prompts and generated output do not enter normal telemetry/logging;
- resources, jobs, sessions and native state must be bounded, cancellable and cleaned on every terminal path;
- emulator evidence never implies physical ARM64/JNI/GGUF, memory, thermal or OEM behavior.

Architecture overview: [`docs/architecture.md`](docs/architecture.md). Repository engineering invariants: [`AGENTS.md`](AGENTS.md).

## Validation

Use the narrowest validation that covers the affected boundary, then let repository CI select stronger gates when risk requires it.

Useful local commands:

```bash
# Formatting
./gradlew spotlessApply

# Repository/static checks
python3 scripts/verify-agent-navigation.py
python3 scripts/verify_architecture.py
./gradlew --no-configuration-cache spotlessCheck detekt verifyNoModelArtifacts

# Full Gradle test surface when appropriate
./gradlew --no-configuration-cache check

# Debug build
./gradlew assembleDebug
```

The repository's canonical command contract is [`.engineering/commands.json`](.engineering/commands.json).

### Validation depth

Repository automation classifies changes by risk rather than using one heavyweight suite for every PR:

- **LEAN** — docs/governance/metadata and cheap repository guards;
- **SCOPED** — contained module change plus direct consumers/tests/lint;
- **STRONG** — public/shared contracts, Binder/control-plane, persistence, native/JNI, packaging or other release-sensitive work;
- **FULL** — promotion/release, global build/CI/toolchain changes or unknown executable scope.

Do not weaken or suppress a legitimate failing gate to obtain a green result.

## Physical-device evidence

Many Harnex claims can be tested automatically; some cannot.

A physical device is required only when the claim depends on real ARM64/JNI/GGUF execution, Android/OEM lifecycle behavior, memory pressure, thermal behavior, Play-delivered identity or other hardware/environment fidelity.

Use:

- [`docs/device-e2e-testing.md`](docs/device-e2e-testing.md) for execution;
- [`docs/device-e2e-evidence.md`](docs/device-e2e-evidence.md) for the privacy-safe evidence contract;
- [`.engineering/e2e.json`](.engineering/e2e.json) for the automated-vs-real-environment boundary.

Never present emulator results as physical-device proof.

## Dependencies

Dependency locking is enabled. When intentionally adding or updating a dependency:

```bash
./gradlew dependencies --write-locks
```

Review the resulting lock changes. Do not introduce dynamic versions such as `latest.release`, `+` or unbounded ranges.

A `llama.cpp` pin change is release-sensitive: review the upstream diff and run the applicable native, Android, model and benchmark evidence before promotion.

## Documentation

Durable behavior and its documentation ship together. Start from [`docs/README.md`](docs/README.md) to find the canonical owner before creating a new document.

For docs-only changes, prefer improving an existing owner over adding another file. Keep the root README adoption-focused; detailed implementation contracts belong in focused docs.

## Pull requests

A good PR should make the outcome easy to review:

1. explain the observable change and why it matters;
2. keep the diff focused on the owning boundary;
3. identify compatibility, privacy/security, lifecycle or resource risks when applicable;
4. include the tests/evidence that prove the change;
5. update affected durable documentation;
6. leave physical/release-only evidence explicitly pending rather than overstating completion.

Before integration, repository preflight records exact source/base identity and runs the required automated gates. Promotion to `main` requires the release contract, not merely a green unit-test suite.

## Security and sensitive data

Do not commit:

- GGUF/GGML model binaries;
- signing keys, passwords or credentials;
- prompts, generated output or private user documents as test/evidence artifacts;
- private Android paths or sensitive diagnostic exports.

For security reporting, see [`SECURITY.md`](SECURITY.md).

## License

By contributing, you agree that your contributions will be licensed under the repository's MIT license.
