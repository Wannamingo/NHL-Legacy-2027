"""Collect a frame and the current native log from the RG477M test build."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--name", default="diagnostic-current")
parser.add_argument("--frame-only", action="store_true")
parser.add_argument("--serial", default="RG477M01025672")
parser.add_argument("--delay", type=float, default=0)
parser.add_argument("--button", choices=["a", "b", "x", "y", "lb", "rb", "back", "start", "up", "down", "left", "right"])
args = parser.parse_args()
if not 0 <= args.delay <= 5:
    raise SystemExit("Snapshot delay must be between 0 and 5 seconds")
if args.button:
    subprocess.run([sys.executable, str(ROOT / "tools/device_button.py"), args.button, "--serial", args.serial], check=True)
time.sleep(args.delay)
adb = ["C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains/platform-tools/adb.exe",
       "-s", args.serial]
output = ROOT / "private/probes"
output.mkdir(parents=True, exist_ok=True)
(output / (args.name + ".png")).write_bytes(
    subprocess.check_output(adb + ["exec-out", "screencap", "-p"]))
if args.frame_only:
    raise SystemExit(0)
names = re.findall(r"nhllegacy_[0-9]+[.]log", subprocess.check_output(
    adb + ["exec-out", "run-as", "org.nhllegacy.port", "ls", "-1", "files/logs"], text=True))
latest = max(names, key=lambda s: int(re.search(r"[0-9]+", s).group()))
log = subprocess.check_output(adb + ["exec-out", "run-as", "org.nhllegacy.port", "cat",
                                   "files/logs/" + latest], text=True, errors="replace")
(output / (args.name + ".log")).write_text(log, encoding="utf-8")
print(latest)
lines = [line for line in log.splitlines() if any(marker in line for marker in
         ("NHL render", "frame summary:", "NHL GPU", "trace", "[error]", "[critical]"))]
print("\n".join(lines[-18:]))
