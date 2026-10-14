"""Bounded native CPU sampling for the running NHL test app, without restarting it."""
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path("C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--seconds", type=int, default=20)
    parser.add_argument("--frequency", type=int, default=1000)
    parser.add_argument("--callgraph", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label):
        parser.error("Use a safe output label")
    if not 1 <= args.seconds <= 30 or not 100 <= args.frequency <= 2000:
        parser.error("Use 1..30 seconds and 100..2000 samples/second")
    adb = [str(TOOLS / "platform-tools/adb.exe"), "-s", args.serial]
    pid = subprocess.check_output(adb + ["shell", "pidof", "org.nhllegacy.port:game"],
                                  text=True).strip()
    if not pid.isdecimal():
        raise SystemExit("Game must already be running; no controls or restart are performed")
    remote = "files/user/cpu-profiles/" + args.label + ".perf.data"
    subprocess.run(adb + ["shell", "run-as", "org.nhllegacy.port", "mkdir", "-p",
                         "files/user/cpu-profiles"], check=True)
    command = ["shell", "run-as", "org.nhllegacy.port", "simpleperf", "record", "--in-app",
               "-p", pid, "-e", "cpu-clock:u", "-f", str(args.frequency),
               "--duration", str(args.seconds), "--size-limit", "24M", "-o", remote]
    if args.callgraph:
        command += ["--call-graph", "fp"]
    output = ROOT / "private/probes/y700"
    output.mkdir(parents=True, exist_ok=True)
    with (output / (args.label + "-record.log")).open("w", encoding="utf-8") as log:
        subprocess.run(adb + command, check=True, stdout=log, stderr=subprocess.STDOUT,
                       timeout=args.seconds + 40)
    (output / (args.label + ".perf.data")).write_bytes(subprocess.check_output(
        adb + ["exec-out", "run-as", "org.nhllegacy.port", "cat", remote]))
    print((output / (args.label + "-record.log")).read_text(), flush=True)
    print(output / (args.label + ".perf.data"), flush=True)


if __name__ == "__main__":
    main()
