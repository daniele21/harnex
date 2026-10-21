#!/usr/bin/env python3
"""Validate the OMBRA vector identity candidate without implying visual approval."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
MASTER_DIR = ROOT / "docs" / "assets" / "consumer" / "ombra" / "master"
SYMBOL_MASTER = MASTER_DIR / "ombra-symbol.svg"
REVIEW_INDEX = MASTER_DIR / "README.md"
SVG_NS = "http://www.w3.org/2000/svg"
EXPECTED_VIEW_BOX = "0 0 100 100"
EXPECTED_NODES = (
    ("path", "document"),
    ("path", "fold"),
    ("rect", "redaction-bar"),
    ("rect", "reveal-slit"),
)
ALLOWED_COLORS = {
    "#15201D",
    "#315C4F",
    "#65D6A6",
    "#DDE9E2",
    "#F6F4EE",
}
COLOR_PATTERN = re.compile(r"#[0-9A-Fa-f]{6}")


class OmbraIdentityError(RuntimeError):
    """Raised when the review-gated identity candidate violates its contract."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise OmbraIdentityError(message)


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def validate_review_gate() -> None:
    require(REVIEW_INDEX.is_file(), f"Missing review index: {REVIEW_INDEX.relative_to(ROOT)}")
    content = REVIEW_INDEX.read_text(encoding="utf-8")
    require("Status: active" in content, "OMBRA identity index must use the active documentation lifecycle status")
    require("REVIEW REQUIRED" in content, "OMBRA symbol must remain explicitly REVIEW REQUIRED")
    require("Do not use this directory to claim OMB-6 identity completion" in content, "OMB-6 completion guard is missing")


def validate_no_unsafe_svg_features(root: ElementTree.Element) -> None:
    forbidden = {"image", "script", "text", "use", "foreignObject"}
    found = sorted({local_name(node.tag) for node in root.iter() if local_name(node.tag) in forbidden})
    require(not found, f"Unsupported SVG nodes in OMBRA candidate: {found}")
    for node in root.iter():
        for name, value in node.attrib.items():
            if name.endswith("href"):
                raise OmbraIdentityError(f"External/reference attribute is not allowed: {name}={value!r}")


def validate_palette(root: ElementTree.Element) -> None:
    used: set[str] = set()
    for node in root.iter():
        for attribute in ("fill", "stroke"):
            value = node.attrib.get(attribute)
            if value and COLOR_PATTERN.fullmatch(value):
                used.add(value.upper())
    unexpected = sorted(used - ALLOWED_COLORS)
    require(not unexpected, f"OMBRA candidate uses colors outside the reviewed palette: {unexpected}")
    require("#15201D" in used, "Opaque redaction ink is missing")
    require("#65D6A6" in used, "SignalMint reveal slit is missing")
    require("#315C4F" in used, "LocalMoss document outline is missing")


def validate_symbol_structure(root: ElementTree.Element) -> None:
    require(root.tag == f"{{{SVG_NS}}}svg", "OMBRA candidate must be an SVG document")
    require(root.attrib.get("viewBox") == EXPECTED_VIEW_BOX, f"OMBRA symbol viewBox must be {EXPECTED_VIEW_BOX}")
    group = root.find(f".//{{{SVG_NS}}}g[@id='ombra-symbol']")
    require(group is not None, "Missing canonical ombra-symbol group")
    actual = tuple((local_name(node.tag), node.attrib.get("id", "")) for node in list(group))
    require(actual == EXPECTED_NODES, f"Unexpected OMBRA symbol node sequence: {actual}")

    for node in list(group):
        node_type = local_name(node.tag)
        if node_type == "path":
            require(bool(node.attrib.get("d")), f"{node.attrib.get('id')} path data is empty")
        elif node_type == "rect":
            for attribute in ("x", "y", "width", "height"):
                raw = node.attrib.get(attribute)
                require(raw is not None, f"{node.attrib.get('id')} is missing {attribute}")
                value = float(raw)
                require(0.0 <= value <= 100.0, f"{node.attrib.get('id')} {attribute} is outside the viewBox")
            width = float(node.attrib["width"])
            height = float(node.attrib["height"])
            x = float(node.attrib["x"])
            y = float(node.attrib["y"])
            require(width > 0.0 and height > 0.0, f"{node.attrib.get('id')} must have positive dimensions")
            require(x + width <= 100.0 and y + height <= 100.0, f"{node.attrib.get('id')} exceeds the viewBox")


def validate_symbol() -> None:
    require(SYMBOL_MASTER.is_file(), f"Missing OMBRA symbol candidate: {SYMBOL_MASTER.relative_to(ROOT)}")
    try:
        root = ElementTree.parse(SYMBOL_MASTER).getroot()
    except ElementTree.ParseError as error:
        raise OmbraIdentityError(f"Invalid OMBRA SVG: {error}") from error
    validate_no_unsafe_svg_features(root)
    validate_symbol_structure(root)
    validate_palette(root)


def main() -> int:
    try:
        validate_review_gate()
        validate_symbol()
    except (OmbraIdentityError, ValueError) as error:
        print(f"OMBRA identity candidate verification failed: {error}", file=sys.stderr)
        return 1
    print("OMBRA identity candidate is structurally deterministic and remains REVIEW REQUIRED.")
    print("This check does not constitute visual approval or OMB-6 completion.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
