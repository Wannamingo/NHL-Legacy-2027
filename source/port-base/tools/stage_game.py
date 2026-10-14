"""Install a local build and copy this user's verified disc to its own app folder.

Never removes device files; skips Xbox firmware updates and optional extracted
assets unless --loose is requested. Copies from a dedicated adb temporary file
into the application's own UID. Requires the installed debug APK.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import shlex

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_XEX = "d818eb15775c1fee34b057b0fc494b211535d07123230ee45bd7d40991c55624"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tools", type=Path, default=Path(os.environ.get("NHL_ANDROID_TOOLS",
        "C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains")))
    parser.add_argument("--game", type=Path, default=Path("D:/Games/NHL Legacy/game"))
    parser.add_argument("--serial", default="RG477M01025672")
    parser.add_argument("--loose", action="store_true")
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    with (args.game / "default.xex").open("rb") as file:
        if hashlib.file_digest(file, "sha256").hexdigest() != EXPECTED_XEX:
            raise SystemExit("XEX does not match the generated NHL code")
    adb = [str(args.tools / "platform-tools/adb.exe"), "-s", args.serial]
    def run(*command):
        subprocess.run(adb + list(map(str, command)), check=True)
    if args.install:
        run("install", "-r", ROOT / "private/apk/NHL-Legacy-RG477M-arm64-dev.apk")
    destination = "files/game"
    temporary = "/data/local/tmp/nhllegacy-port-tests/disc-transfer.bin"
    def app(command, **kwargs):
        return subprocess.run(adb + ["shell", "-T", "run-as", "org.nhllegacy.port", "sh", "-c",
            shlex.quote(command)], check=True, **kwargs)
    app("mkdir -p " + destination)
    run("shell", "mkdir", "-p", "/data/local/tmp/nhllegacy-port-tests")
    files = sorted(path for path in args.game.rglob("*") if path.is_file())
    for file in files:
        relative = file.relative_to(args.game)
        if "$SystemUpdate" in relative.parts or (relative.parts[0].startswith("_") and not args.loose):
            continue
        target = destination + "/" + relative.as_posix()
        stat = app("stat -c %s " + shlex.quote(target) + " 2>/dev/null || true", capture_output=True)
        if stat.stdout.decode().strip() == str(file.stat().st_size):
            print("Already copied", relative, flush=True)
            continue
        print("Copying", relative, flush=True)
        app("mkdir -p " + shlex.quote(str(Path(target).parent).replace("\\", "/")))
        # adb push uses the binary sync protocol. Redirecting binary stdin to
        # adb shell on Windows can stop at Ctrl-Z, silently truncating files.
        run("push", file, temporary)
        app("cp " + temporary + " " + shlex.quote(target))
        stat = app("stat -c %s " + shlex.quote(target), capture_output=True)
        if stat.stdout.decode().strip() != str(file.stat().st_size):
            raise SystemExit("Incomplete device file: " + str(relative))
        run("shell", "rm", "-f", temporary)
    xex = app("sha256sum files/game/default.xex", capture_output=True)
    if xex.stdout.decode().split()[0] != EXPECTED_XEX:
        raise SystemExit("Device XEX checksum failed")
    print("Disc copied. Launch NHL Legacy from the device's app menu.")

if __name__ == "__main__":
    main()
