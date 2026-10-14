"""Generate Android guest hooks and preserve timestamps of unchanged chunks."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").digest()

subprocess.run([sys.executable, ROOT / "tools/prepare_game.py"], check=True)
directory = ROOT / "generated/default"
previous = {p: (p.stat().st_mtime_ns, digest(p)) for p in directory.glob("*") if p.is_file()}
log = ROOT / "private/android-codegen.log"
with log.open("w", encoding="utf-8") as output:
    result = subprocess.run([ROOT / "private/build-host-codegen/cli/rexglue.exe", "--force",
                             "codegen", ROOT / "private/nhllegacy_manifest.toml"],
                            cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
changed = []
for path, (modified, checksum) in previous.items():
    if path.exists() and digest(path) == checksum:
        os.utime(path, ns=(path.stat().st_atime_ns, modified))
    else:
        changed.append(path.name)
print("Changed generated chunks:", changed)
print("\n".join(log.read_text(errors="replace").splitlines()[-12:]))
raise SystemExit(result.returncode)
