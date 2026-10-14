# Harnex — CI Agent Instructions

- Central local Mac runners belong to `daniele21/local-runner-infra`, not Harnex; do not use central labels directly as Harnex `runs-on`.
- `.github/workflows/local-ci.yml` dispatches trusted source commits via the `CENTRAL_DISPATCH_TOKEN` secret.
- `.github/ci/local-test.sh` is a bounded initial Mac repository-health lane. Verify central run logs and `local-ci/mac` on the originating commit; green dispatch is not a test result.
- Preserve Android native/JNI, Gradle, emulator, packaging, publication, required checks and the protected dev/main PR flow. The Mac lane does not replace them.
- Central integration guide: https://github.com/daniele21/local-runner-infra/blob/main/docs/integration-guide.md
