"""Device integration check: press a real evdev gamepad button in NHL only."""
import argparse
import re
import subprocess
import time
from pathlib import Path

BUTTONS = {"a": 304, "b": 305, "x": 307, "y": 308, "lb": 310, "rb": 311,
           "back": 314, "start": 315, "up": 544, "down": 545, "left": 546, "right": 547}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("button", choices=BUTTONS)
    parser.add_argument("--serial", default="RG477M01025672")
    parser.add_argument("--tools", type=Path, default=Path("C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains"))
    args = parser.parse_args()
    adb = [str(args.tools / "platform-tools/adb.exe"), "-s", args.serial]
    def get(*command):
        return subprocess.check_output(adb + list(command)).decode(errors="replace")
    def run(*command):
        subprocess.run(adb + list(map(str, command)), check=True)
    activity = get("shell", "dumpsys", "activity", "activities")
    resumed = [line for line in activity.splitlines() if "ResumedActivity" in line]
    if not any("org.nhllegacy.port" in line for line in resumed):
        raise SystemExit("NHL must be the resumed activity; no button injected")
    devices = get("shell", "getevent", "-lp")
    event = None
    for block in re.split(r"add device \d+: ", devices)[1:]:
        if 'name:     "Xbox Wireless Controller"' in block:
            event = block.splitlines()[0].strip()
    if not event or not re.fullmatch(r"/dev/input/event\d+", event):
        raise SystemExit("Known RG477M gamepad was not found")
    code = BUTTONS[args.button]
    try:
        run("shell", "sendevent", event, 1, code, 1)
        run("shell", "sendevent", event, 0, 0, 0)
        time.sleep(0.2)
    finally:
        run("shell", "sendevent", event, 1, code, 0)
        run("shell", "sendevent", event, 0, 0, 0)
    print("Pressed", args.button, "through", event)

if __name__ == "__main__":
    main()
