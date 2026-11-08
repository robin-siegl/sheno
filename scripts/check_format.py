"""Check basic text formatting in tracked source and configuration."""
from pathlib import Path
import subprocess

SUFFIXES = {".kt", ".kts", ".xml", ".yml", ".py", ".md", ".properties"}
paths = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
bad = []
for name in paths:
    path = Path(name)
    if path.suffix not in SUFFIXES:
        continue
    raw = path.read_bytes()
    if b"\r" in raw or not raw.endswith(b"\n") or any(line.rstrip() != line for line in raw.splitlines()):
        bad.append(name)
if bad:
    raise SystemExit("Check line endings or trailing whitespace in: " + ", ".join(bad))
