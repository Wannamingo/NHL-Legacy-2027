"""Read and preserve NHL's shader cache for before/after restart checks."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ADB = ["C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains/platform-tools/adb.exe",
       "-s", "RG477M01025672"]
parser = argparse.ArgumentParser()
parser.add_argument("--label", required=True)
parser.add_argument("--serial", default="RG477M01025672")
args = parser.parse_args()
ADB[-1] = args.serial
if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label):
    raise SystemExit("Invalid label")
new_root = "files/game/_android_cache/shaders/"
legacy_root = "files/user/cache/shaders/"
exists = subprocess.run(ADB + ["shell", "run-as", "org.nhllegacy.port", "test", "-d", new_root],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
remote = new_root if exists else legacy_root
output = ROOT / "private/probes/cache-checks" / args.label
output.mkdir(parents=True, exist_ok=True)
result = {}
for category, allowed in (
        ("shareable", r"454109EC(?:[.]xsh|[.]fbo[.]vk[.]xpso)"),
        ("device", r"454109EC-[A-Fa-f0-9-]+-v1[.]vkc")):
    directory = remote + category + "/"
    listing = subprocess.run(ADB + ["exec-out", "run-as", "org.nhllegacy.port", "ls", "-1", directory],
                             capture_output=True, text=True)
    if listing.returncode != 0:
        continue
    for name in listing.stdout.splitlines():
        if not re.fullmatch(allowed, name):
            continue
        data = subprocess.check_output(ADB + ["exec-out", "run-as", "org.nhllegacy.port", "cat", directory + name])
        destination = output / category / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        result[category + "/" + name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                                       "header_hex": data[:56].hex(), "remote": directory + name}
(output / "cache.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))
