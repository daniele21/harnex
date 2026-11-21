#!/usr/bin/env bash
# Cheap, deterministic Harnex checks only. No Android emulator, no model downloads.
set -euo pipefail
python3 -m unittest discover -s scripts -p 'test_detect_ci_scope.py' -v
python3 -m unittest discover -s scripts -p 'test_verify_architecture.py' -v
