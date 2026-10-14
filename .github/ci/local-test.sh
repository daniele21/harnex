#!/usr/bin/env bash
# Initial repository-health checks on the shared Mac runner.
# NOT a substitute for Android/Gradle, JNI, emulator, packaging or release gates.
set -euo pipefail

python3 scripts/verify_no_tracked_generated_artifacts.py
python3 scripts/verify_repository.py
python3 scripts/verify_operations.py
python3 scripts/verify_e2e.py
python3 scripts/verify_stage_environment_policy.py
python3 scripts/verify_agent_context.py
python3 scripts/verify_product_experience.py
