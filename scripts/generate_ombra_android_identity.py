#!/usr/bin/env python3
"""Generate OMBRA Android launcher identity only from an explicitly approved master.

The default verification mode is intentionally safe for review branches: it proves that
production generation remains blocked while the canonical symbol is REVIEW REQUIRED.
The generated Android XML is additionally pinned to the exact reviewed SVG bytes so a
later geometry edit fails closed until the generator is intentionally reconciled.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MASTER_DIR = ROOT / "docs" / "assets" / "consumer" / "ombra" / "master"
REVIEW_INDEX = MASTER_DIR / "README.md"
SYMBOL_MASTER = MASTER_DIR / "ombra-symbol.svg"
APPROVED_STATUS = "**APPROVED**"
REVIEW_REQUIRED_STATUS = "**REVIEW REQUIRED**"
SYMBOL_ROW_TOKEN = "[`ombra-symbol.svg`](ombra-symbol.svg)"
SUPPORTED_SYMBOL_SHA256 = "30d5adeadf05b1493eb589a1dd914807077996ff5371488664e657f52f0f1742"

OUTPUTS = {
    ROOT / "apps/local-llm-console/src/main/res/drawable/ombra_launcher_foreground.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"
    android:width=\"108dp\"
    android:height=\"108dp\"
    android:viewportWidth=\"100\"
    android:viewportHeight=\"100\">
    <path android:fillColor=\"#F6F4EE\" android:strokeColor=\"#315C4F\" android:strokeWidth=\"6\" android:strokeLineJoin=\"round\" android:pathData=\"M20,8 H60 L82,30 V92 H20 Z\" />
    <path android:fillColor=\"#DDE9E2\" android:strokeColor=\"#315C4F\" android:strokeWidth=\"6\" android:strokeLineJoin=\"round\" android:pathData=\"M60,8 V30 H82 Z\" />
    <path android:fillColor=\"#15201D\" android:pathData=\"M33,49 H69 A4,4 0,0 1,73 53 V62 A4,4 0,0 1,69 66 H33 A4,4 0,0 1,29 62 V53 A4,4 0,0 1,33 49 Z\" />
    <path android:fillColor=\"#65D6A6\" android:pathData=\"M38.5,56 H59.5 A1.5,1.5 0,0 1,61 57.5 A1.5,1.5 0,0 1,59.5 59 H38.5 A1.5,1.5 0,0 1,37 57.5 A1.5,1.5 0,0 1,38.5 56 Z\" />
</vector>
""",
    ROOT / "apps/local-llm-console/src/main/res/drawable/ombra_launcher_monochrome.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"
    android:width=\"108dp\"
    android:height=\"108dp\"
    android:viewportWidth=\"100\"
    android:viewportHeight=\"100\">
    <path android:fillColor=\"#FF000000\" android:pathData=\"M20,8 H60 L82,30 V92 H20 Z\" />
    <path android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"6\" android:pathData=\"M29,57.5 H73\" />
</vector>
""",
    ROOT / "apps/local-llm-console/src/main/res/values/ombra_launcher_colors.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <color name=\"ombra_launcher_background\">#15201D</color>
</resources>
""",
    ROOT / "apps/local-llm-console/src/main/res/mipmap-anydpi-v26/ic_launcher.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">
    <background android:drawable=\"@color/ombra_launcher_background\" />
    <foreground android:drawable=\"@drawable/ombra_launcher_foreground\" />
</adaptive-icon>
""",
    ROOT / "apps/local-llm-console/src/main/res/mipmap-anydpi-v33/ic_launcher.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">
    <background android:drawable=\"@color/ombra_launcher_background\" />
    <foreground android:drawable=\"@drawable/ombra_launcher_foreground\" />
    <monochrome android:drawable=\"@drawable/ombra_launcher_monochrome\" />
</adaptive-icon>
""",
}


class IdentityGenerationError(RuntimeError):
    """Raised when production identity generation is not allowed."""


def symbol_status() -> str:
    if not REVIEW_INDEX.is_file():
        raise IdentityGenerationError("Missing canonical OMBRA identity index")
    for line in REVIEW_INDEX.read_text(encoding="utf-8").splitlines():
        if SYMBOL_ROW_TOKEN in line:
            if APPROVED_STATUS in line:
                return "APPROVED"
            if REVIEW_REQUIRED_STATUS in line:
                return "REVIEW REQUIRED"
            raise IdentityGenerationError("OMBRA symbol row has no recognized review status")
    raise IdentityGenerationError("OMBRA symbol row is missing from the canonical identity index")


def validate_supported_master() -> None:
    if not SYMBOL_MASTER.is_file():
        raise IdentityGenerationError("OMBRA symbol master is missing")
    digest = hashlib.sha256(SYMBOL_MASTER.read_bytes()).hexdigest()
    if digest != SUPPORTED_SYMBOL_SHA256:
        raise IdentityGenerationError(
            "OMBRA symbol geometry changed; reconcile and review the Android generator before production generation",
        )


def verify_gate() -> None:
    status = symbol_status()
    validate_supported_master()
    if status == "REVIEW REQUIRED":
        print("OMBRA Android identity generation is correctly blocked: symbol is REVIEW REQUIRED.")
        return
    print("OMBRA symbol is explicitly APPROVED and matches the generator-pinned reviewed geometry.")


def generate() -> None:
    status = symbol_status()
    if status != "APPROVED":
        raise IdentityGenerationError(
            "Refusing to generate production Android identity: canonical symbol is not explicitly APPROVED",
        )
    validate_supported_master()

    for path, content in OUTPUTS.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        print(f"generated {path.relative_to(ROOT)}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--generate", action="store_true", help="Generate production Android identity resources")
    args = parser.parse_args()
    try:
        if args.generate:
            generate()
        else:
            verify_gate()
    except IdentityGenerationError as error:
        print(f"OMBRA Android identity generation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
