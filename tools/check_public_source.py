#!/usr/bin/env python3
"""Static public-source guard. A passing scan is not a comprehensive secret audit."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = {".kt", ".kts", ".xml", ".yml", ".yaml", ".md", ".py", ".properties"}
SPECIAL = {".gitignore"}
SKIP = {".git", ".gradle", "build", ".idea"}
RULES = {
    "private key": re.compile(r"-----BEGIN [^\n]*PRIVATE KEY-----"),
    "GitHub access token": re.compile(r"\bgh[opsur]_[A-Za-z0-9]{30,}\b"),
    "Google API key": re.compile(r"\bAIza[A-Za-z0-9_-]{32,}\b"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
}
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
SYNTHETIC = {"trusted.example@attacker.example"}
BLOCKED_NAME = re.compile(r"(?:^|/)(?:google-services\.json|local\.properties|[^/]+\.(?:jks|keystore|p12|pfx))$", re.I)

def main() -> int:
    failures = []
    total = 0
    for path in ROOT.rglob("*"):
        if not path.is_file() or any(part in SKIP for part in path.relative_to(ROOT).parts):
            continue
        rel = path.relative_to(ROOT).as_posix()
        if BLOCKED_NAME.search(rel):
            failures.append(f"{rel}: forbidden public file")
        if path.suffix not in SUFFIXES and path.name not in SPECIAL:
            continue
        text = path.read_text(encoding="utf-8")
        total += 1
        if rel == "tools/check_public_source.py":
            continue
        for name, pattern in RULES.items():
            if pattern.search(text):
                failures.append(f"{rel}: possible {name}")
        for value in EMAIL.findall(text):
            if value not in SYNTHETIC and not value.lower().endswith(("@example.com", "@example.org", "@example.test")):
                failures.append(f"{rel}: non-example email")
    print(f"Checked {total} public source files; violations: {len(failures)}")
    for failure in failures:
        print(failure, file=sys.stderr)
    return int(bool(failures))

if __name__ == "__main__":
    raise SystemExit(main())
