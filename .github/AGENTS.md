# Harnex GitHub CI — scoped agent guidance

- The Mac runners are attached **only** to the private `daniele21/local-runner-infra` repository. Do not register them in Harnex or set Harnex jobs to `runs-on: self-hosted`.
- `.github/workflows/local-ci.yml` is a **hosted dispatch** restricted to exact `dev` push/manual events whose GitHub actor is `daniele21`; never accept fork PR source, alternate branches or user-supplied shell commands. The central executor independently checks that the SHA belongs to protected `dev`.
- The central repository uses the source-owned `.github/ci/local-test.sh` from the exact checkout. It is a supplementary Mac ARM64 owner-local unit lane, not the source of truth for FULL Android/Native/Emulator/JNI packaging or release.
- Keep GitHub-hosted `Validate`, APK artifacts, emulator evidence and required checks. A successful dispatch means only **request accepted**; inspect `local-ci/mac` on the exact Harnex source SHA and the central run.
- `CENTRAL_DISPATCH_TOKEN` must be a narrowly scoped owner-owned credential for the private infrastructure repository; never store it in source code, local temp files or logs. `CENTRAL_SOURCE_TOKEN` must have Harnex read/status scopes in central infrastructure.
- The Mac has no VM sandbox. Limit Gradle workers, use Harnex-only cache and runner-owned temporary paths, and do not execute or authorize unreviewed PR code.
- Terraform owns the central workflow and runner registrations; coordinate source changes with `daniele21/local-runner-infra` and its [integration contract](https://github.com/daniele21/local-runner-infra/blob/main/docs/integration-guide.md). Do not run Terraform from a new state.
