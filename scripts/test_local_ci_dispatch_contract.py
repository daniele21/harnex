"""Static source-side contract for the protected Harnex Mac dispatcher."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
DISPATCH = ROOT / ".github/workflows/local-ci.yml"
SCRIPT = ROOT / ".github/ci/local-test.sh"
BOOTSTRAP = ROOT / ".github/ci/bootstrap-android-sdk.sh"


class LocalMacDispatchContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dispatch = DISPATCH.read_text(encoding="utf-8")
        cls.script = SCRIPT.read_text(encoding="utf-8")
        cls.bootstrap = BOOTSTRAP.read_text(encoding="utf-8")

    def test_only_trusted_dev_owner_push_dispatches(self):
        for value in (
            "branches: [dev]",
            "github.repository == 'daniele21/harnex'",
            "github.ref == 'refs/heads/dev'",
            "github.actor == 'daniele21'",
            "secrets.CENTRAL_DISPATCH_TOKEN",
            "client_payload[sha]=$SOURCE_SHA",
            "event_type=run-ci",
        ):
            with self.subTest(value=value):
                self.assertIn(value, self.dispatch)
        self.assertNotIn("pull_request:", self.dispatch)
        self.assertNotIn("runs-on: self-hosted", self.dispatch)
        self.assertIn('[[ -n "${GH_TOKEN:-}" ]]', self.dispatch)

    def test_supplemental_mac_lane_is_bounded_and_fail_closed(self):
        for value in (
            "set -euo pipefail",
            '"Darwin"',
            '"arm64"',
            "--max-workers=2",
            ":models:model-download:testDebugUnitTest",
            ":integrations:android-embedded-sdk:testDebugUnitTest",
            "GITHUB_WORKSPACE",
            "git clean -ffdX -- .",
            "trap cleanup EXIT",
            "NOT FULL Android/release validation",
            "bash .github/ci/bootstrap-android-sdk.sh",
        ):
            with self.subTest(value=value):
                self.assertIn(value, self.script)
        self.assertNotIn("OPENAI_API_KEY", self.script)
        self.assertNotIn("workflow_dispatch inputs", self.dispatch)

    def test_bootstrap_is_pinned_isolated_and_fail_closed(self):
        for value in (
            "commandlinetools-mac_arm64-15859902_latest.zip",
            "835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e",
            'cache="$HOME/Library/Caches/harnex-local-ci"',
            'sdk="$cache/android-sdk"',
            '"$RUNNER_TEMP/harnex-sdk.XXXXXXXX"',
            'expected=',
            '[[ "$actual" == "$expected" ]]',
            '"platforms;android-36" "build-tools;36.0.0" "platform-tools"',
            'have_platform "$sdk"',
            'rmdir "$lock"',
        ):
            with self.subTest(value=value):
                self.assertIn(value, self.bootstrap)
        for forbidden in ("sudo ", "brew install", "rm -rf $HOME", "curl | bash"):
            self.assertNotIn(forbidden, self.bootstrap)


if __name__ == "__main__":
    unittest.main()
