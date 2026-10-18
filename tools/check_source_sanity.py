#!/usr/bin/env python3
"""Fast, dependency-free preflight for known E-QR Kotlin/Gradle regressions.

This is a static guard, NOT a substitute for Gradle compilation or Android tests.
Run from any working directory: python3 tools/check_source_sanity.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
KOTLIN_DIR = ROOT / "app" / "src" / "main" / "java"
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"
ROOT_GRADLE_FILE = ROOT / "build.gradle.kts"
SCAN_CORE_FILE = KOTLIN_DIR / "com" / "embasic" / "eqr" / "core" / "scan" / "ScanCore.kt"

FORBIDDEN_IMPORTS = {
    "androidx.compose.foundation.layout.weight":
        "Modifier.weight belongs to RowScope/ColumnScope, not a public top-level import",
}

COMPOSE_DELEGATE = re.compile(
    r"\bby\s+(?:remember(?:Saveable)?\b|mutableStateOf\s*<|mutableStateOf\s*\(|"
    r"[^\n]*?\.collectAsStateWithLifecycle\s*\()"
)
COMPOSE_MUTABLE_DELEGATE = re.compile(
    r"\bvar\s+\w+\s+by\s+(?:remember(?:Saveable)?\b|mutableStateOf\b|"
    r"[^\n]*?\.collectAsStateWithLifecycle\s*\()"
)
LITERAL_NEWLINE_BETWEEN_DEPENDENCIES = re.compile(
    r"\)\s*\\n\s*(?:implementation|api|ksp|testImplementation|"
    r"androidTestImplementation|debugImplementation)\s*\("
)


def check_sources() -> tuple[list[str], int]:
    errors: list[str] = []
    files = sorted(KOTLIN_DIR.rglob("*.kt")) if KOTLIN_DIR.is_dir() else []
    if not files:
        return [f"{KOTLIN_DIR}: no Kotlin files found"], 0

    for file in files:
        text = file.read_text(encoding="utf-8")
        relative = file.relative_to(ROOT)
        imports = set(re.findall(r"(?m)^\s*import\s+([\w.]+)\s*$", text))
        for forbidden, explanation in FORBIDDEN_IMPORTS.items():
            if forbidden in imports:
                errors.append(f"{relative}: forbidden import {forbidden} — {explanation}")
        if COMPOSE_DELEGATE.search(text) and (
            "androidx.compose.runtime.getValue" not in imports
        ):
            errors.append(
                f"{relative}: Compose 'by' state delegate requires "
                "import androidx.compose.runtime.getValue"
            )
        if COMPOSE_MUTABLE_DELEGATE.search(text) and (
            "androidx.compose.runtime.setValue" not in imports
        ):
            errors.append(
                f"{relative}: mutable Compose 'by' state delegate requires "
                "import androidx.compose.runtime.setValue"
            )

    if not GRADLE_FILE.is_file():
        errors.append(f"{GRADLE_FILE}: missing")
    elif LITERAL_NEWLINE_BETWEEN_DEPENDENCIES.search(
        GRADLE_FILE.read_text(encoding="utf-8")
    ):
        errors.append(
            "app/build.gradle.kts: literal backslash+n between Gradle "
            "dependency declarations; use a real newline"
        )
    if ROOT_GRADLE_FILE.is_file():
        root_gradle = ROOT_GRADLE_FILE.read_text(encoding="utf-8")
        agp = re.search(
            r'id\("com\.android\.application"\)\s+version\s+"([\d.]+)"',
            root_gradle,
        )
        compose = re.search(
            r'id\("org\.jetbrains\.kotlin\.plugin\.compose"\)\s+version\s+"([\d.]+)"',
            root_gradle,
        )
        if agp and compose:
            agp_version = tuple(int(x) for x in agp.group(1).split("."))
            compose_version = tuple(int(x) for x in compose.group(1).split("."))
            if (9, 3) <= agp_version[:2] < (9, 4) and compose_version < (2, 4, 20):
                errors.append(
                    "build.gradle.kts: AGP 9.3 requires Kotlin/Compose compiler "
                    "plugin >=2.4.20 for full official compatibility"
                )

    if SCAN_CORE_FILE.is_file():
        scan_source = SCAN_CORE_FILE.read_text(encoding="utf-8")
        # ScanFrame is intentionally extensible (also used in test fakes).
        # Kotlin requires an else branch for when(frame) *expressions*.
        for name, end_marker in (
            ("inputAndSize", "val (input, sourceWidth, sourceHeight)"),
            ("results", "results.mapNotNull"),
        ):
            expression = re.search(
                rf"val\s+{name}\s*=\s*when\s*\(\s*frame\s*\)\s*\{{",
                scan_source,
            )
            if expression is None:
                errors.append(f"ScanCore.kt: missing {name} when(frame) expression")
                continue
            end = scan_source.find(end_marker, expression.end())
            block = scan_source[expression.end():end if end >= 0 else None]
            if not re.search(r"(?m)^\s*else\s*->", block):
                errors.append(
                    f"ScanCore.kt: non-exhaustive when(frame) in {name}; "
                    "handle unknown ScanFrame implementations explicitly"
                )

    # The private Windows packager is intentionally excluded from public CI.

    return errors, len(files)


def main() -> int:
    errors, count = check_sources()
    if errors:
        print("E-QR source preflight FAILED:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"E-QR source preflight PASSED ({count} Kotlin files).")
    print("This does not prove Android compilation or runtime behavior.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
