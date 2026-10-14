"""Render the captured frame on RG477M with optional draw-range suppression."""
import argparse
from pathlib import Path
import shutil
import subprocess
import time
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path("C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains")
parser = argparse.ArgumentParser()
parser.add_argument("--stage", action="store_true")
parser.add_argument("--serial", default="RG477M01025672")
mode = parser.add_mutually_exclusive_group()
mode.add_argument("--serve", action="store_true")
mode.add_argument("--request", action="store_true", help="Send one variant to the running replayer")
parser.add_argument("--trace", type=Path, default=ROOT / "private/probes/454109EC_79726.xtr")
parser.add_argument("--first", type=int, default=-1)
parser.add_argument("--last", type=int, default=-1)
parser.add_argument("--address", type=lambda s: int(s, 16), default=0x1CF32000)
parser.add_argument("--width", type=int, default=1280)
parser.add_argument("--height", type=int, default=720)
parser.add_argument("--name", default="replay-baseline")
parser.add_argument("--config", type=Path, help="Optional renderer TOML for this replayer only")
parser.add_argument("--iterations", type=int, default=1, help="GPU-completed frame runs, excluding one warmup")
parser.add_argument("--readback", choices=["full", "fast", "none"], default="fast", help="Readback in measured runs; pixel export always uses full")
parser.add_argument("--resident", action="store_true", help="Keep identical captured inputs resident during repeated samples")
args = parser.parse_args()
if args.request and args.stage:
    raise SystemExit("Stage before starting the persistent replayer")
if not 1 <= args.iterations <= 120:
    raise SystemExit("Iterations must be between 1 and 120")
if args.request and (args.config or args.iterations != 1 or args.resident):
    raise SystemExit("Set config/iterations/memory mode when starting the replayer")
if not 1 <= args.width <= 4096 or not 1 <= args.height <= 4096:
    raise SystemExit("Invalid buffer dimensions")
if not args.name.replace("-", "").replace("_", "").isalnum():
    raise SystemExit("Invalid output name")
adb = [str(TOOLS / "platform-tools/adb.exe"), "-s", args.serial]
output = ROOT / "private/probes/replay"
output.mkdir(parents=True, exist_ok=True)
remote = "/data/local/tmp/nhllegacy-port-tests/replay"
def run(*command):
    subprocess.run(adb + list(map(str, command)), check=True)
if args.stage:
    run("shell", "mkdir", "-p", remote)
    for name, source in [("nhl_trace_replay", ROOT / "private/build-arm64/nhl_trace_replay"),
                         ("librexruntime.so", ROOT / "private/reference-rexglue-android/out/linux-arm64/librexruntime.so")]:
        target = output / name
        shutil.copyfile(source, target)
        subprocess.run([str(TOOLS / "android-ndk-r30/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"),
                        "--strip-unneeded", str(target)], check=True)
        run("push", target, remote + "/" + name)
    run("push", args.trace, remote + "/frame.xtr")
    run("shell", "chmod", "755", remote + "/nhl_trace_replay")
config = "-"
if args.config:
    if not args.config.is_file():
        raise SystemExit("Renderer config does not exist")
    run("push", args.config, remote + "/benchmark-renderer.toml")
    config = "benchmark-renderer.toml"
command = (f"cd {remote} && LD_LIBRARY_PATH=. ./nhl_trace_replay frame.xtr {args.name}.ppm "
           f"{args.first} {args.last} {args.address:X} {args.width} {args.height} {config} {args.iterations} {args.readback} "
           f"{'resident' if args.resident else 'restore'}")
if args.serve:
    command = (f"cd {remote} && LD_LIBRARY_PATH=. ./nhl_trace_replay frame.xtr unused.ppm "
               f"--serve -1 {args.address:X} {args.width} {args.height} {config} {args.iterations} {args.readback} "
               f"{'resident' if args.resident else 'restore'}")
if args.request:
    request_id = int(time.time()) & 0x7fffffff
    pending = output / "replay-command.txt"
    pending.write_text(f"{request_id} {args.first} {args.last} {args.address:X} {args.name}.ppm\n", encoding="ascii")
    run("push", pending, remote + "/replay-command.pending")
    run("shell", "mv", remote + "/replay-command.pending", remote + "/replay-command.txt")
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        done = subprocess.run(adb + ["exec-out", "cat", remote + "/replay-done.txt"], capture_output=True, text=True)
        if done.returncode == 0 and done.stdout.strip() == str(request_id):
            break
        time.sleep(0.25)
    else:
        raise SystemExit("Persistent replay did not finish within 45 seconds")
else:
    with (output / (args.name + ".log")).open("w", encoding="utf-8") as log:
        result = subprocess.run(adb + ["shell", command], stdout=log, stderr=subprocess.STDOUT)
    print((output / (args.name + ".log")).read_text(encoding="utf-8", errors="replace")[-5000:])
    if result.returncode:
        raise SystemExit(result.returncode)
if args.serve:
    raise SystemExit(0)
run("pull", remote + "/" + args.name + ".ppm", output / (args.name + ".ppm"))
with Image.open(output / (args.name + ".ppm")) as frame:
    frame.save(output / (args.name + ".png"))
print(output / (args.name + ".png"))
