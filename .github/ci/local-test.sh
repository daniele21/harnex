#!/usr/bin/env bash
set -euo pipefail

python3 scripts/verify-agent-navigation.py
python3 scripts/verify_repository.py
python3 scripts/verify_operations.py
