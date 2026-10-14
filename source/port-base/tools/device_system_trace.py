"""Capture a bounded system trace without restarting the running game."""
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ADB = "C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains/platform-tools/adb.exe"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--label", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label):
        raise SystemExit("Invalid trace label")
    adb = [ADB, "-s", args.serial]
    folder = ROOT / "private/probes/y700"
    config = folder / "frame-pacing.pbtxt"
    if not config.is_file():
        raise SystemExit("Missing bounded 20-second trace configuration")
    remote_trace = "/data/misc/perfetto-traces/" + args.label + ".pftrace"
    sources = subprocess.run(adb + ["shell", "perfetto", "--query", "--long"],
                             capture_output=True, text=True, check=True, timeout=15)
    (folder / (args.label + "-sources.txt")).write_text(sources.stdout, encoding="utf-8")
    print("Capturing system scheduling and presentation for 20 seconds", flush=True)
    # Perfetto's SELinux domain cannot read ADB-owned /data/local/tmp configs.
    result = subprocess.run(adb + ["shell", "perfetto", "--txt", "-c", "-",
                                   "-o", remote_trace], input=config.read_text(encoding="utf-8"),
                            capture_output=True, text=True, timeout=45)
    (folder / (args.label + "-capture.txt")).write_text(result.stdout + result.stderr, encoding="utf-8")
    result.check_returncode()
    target = folder / (args.label + ".pftrace")
    subprocess.run(adb + ["pull", remote_trace, str(target)], check=True, timeout=30)
    print(str(target), target.stat().st_size, "bytes")

if __name__ == "__main__":
    main()
