"""Run a captured GPU frame only when NHL's game process is closed.

Do not suspend a running Vulkan game: a delayed in-flight submission caused
device loss during the earlier isolation experiment. Preserve that failure
guard instead of repeating STOP/CONT on the game process.
"""
import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(add_help=False)
parser.add_argument("--serial", default="RG477M01025672")
args, _ = parser.parse_known_args()
ADB = ["C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains/platform-tools/adb.exe",
       "-s", args.serial]
pid = subprocess.run(ADB + ["shell", "pidof", "org.nhllegacy.port:game"],
                     capture_output=True, text=True)
if pid.stdout.strip() or pid.returncode not in (0, 1):
    raise SystemExit("Close NHL normally before standalone replay; the game will not be suspended")
print("NHL game is closed; running standalone replay", flush=True)
result = subprocess.run([sys.executable, str(ROOT / "tools/device_replay.py"), *sys.argv[1:]], timeout=150)
raise SystemExit(result.returncode)
