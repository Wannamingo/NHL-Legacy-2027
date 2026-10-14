"""Copy the latest private GPU frame capture from the development package."""
from pathlib import Path
import argparse
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="RG477M01025672")
args = parser.parse_args()
adb = ["C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains/platform-tools/adb.exe",
       "-s", args.serial]
folder = "files/user/gpu-trace/"
names = subprocess.check_output(adb + ["exec-out", "run-as", "org.nhllegacy.port", "ls", "-t", "-1", folder], text=True).splitlines()
name = next(name for name in names if name.endswith(".xtr") and "/" not in name)
output = Path(__file__).resolve().parents[1] / "private/probes" / name
with output.open("wb") as f:
    child = subprocess.Popen(adb + ["exec-out", "run-as", "org.nhllegacy.port", "cat", folder + name], stdout=subprocess.PIPE)
    shutil.copyfileobj(child.stdout, f)
    if child.wait() != 0:
        raise SystemExit("Trace copy failed")
print(output, output.stat().st_size)
