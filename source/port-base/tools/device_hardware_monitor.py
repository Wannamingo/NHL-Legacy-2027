"""Bounded fresh HAL samples; unavailable GPU clocks remain unavailable."""
import argparse
import csv
import json
import math
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
ADB = "C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains/platform-tools/adb.exe"

def parse_thermal(text):
    current = text.partition("Current temperatures from HAL:")[2].partition(
        "Current cooling devices from HAL:")[0]
    values = [(float(value), int(kind), name) for value, kind, name in re.findall(
        r"Temperature\{mValue=([-0-9.]+), mType=(\d+), mName=([^,}]+)", current)]
    def maximum(kind):
        valid = [value for value, typ, _ in values if typ == kind and -20 <= value <= 130]
        return max(valid) if valid else None
    skin = next((value for value, _, name in values if name == "skin"), None)
    status = re.search(r"Thermal Status:\s*(\d+)", text)
    return {"skin_c": skin, "gpu_c": maximum(1), "cpu_c": maximum(0),
            "thermal_status": int(status[1]) if status else -1}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--seconds", type=float, default=60)
    parser.add_argument("--interval", type=float, default=3)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label) or not 5 <= args.seconds <= 600 or not 2 <= args.interval <= 10:
        raise SystemExit("Invalid duration, interval, or label")
    adb = [ADB, "-s", args.serial]
    def read(*cmd):
        return subprocess.run(adb + list(cmd), capture_output=True, text=True, timeout=15)
    folder = ROOT / "private/probes/y700"
    folder.mkdir(parents=True, exist_ok=True)
    rows = []
    started = time.monotonic()
    gpu_path = "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"
    gpu_available = True
    last_thermal = ""
    try:
        while time.monotonic() - started < args.seconds:
            thermal = read("shell", "dumpsys", "thermalservice")
            if thermal.returncode: raise RuntimeError(thermal.stderr.strip())
            last_thermal = thermal.stdout
            row = parse_thermal(last_thermal)
            row["gpu_mhz"] = None
            if gpu_available:
                gpu = read("shell", "cat", gpu_path)
                if gpu.returncode or not gpu.stdout.strip().isdigit():
                    gpu_available = False
                else: row["gpu_mhz"] = int(gpu.stdout) / 1e6
            cpu = read("shell", "cat", "/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq")
            row["cpu_prime_mhz"] = int(cpu.stdout) / 1000 if cpu.stdout.strip().isdigit() else None
            uptime = read("shell", "cat", "/proc/uptime")
            row["boot_seconds"] = float(uptime.stdout.split()[0])
            row["host_elapsed_seconds"] = time.monotonic() - started
            rows.append(row)
            # Strict numeric payload, atomically replace in app private storage.
            values = [row[key] for key in ("boot_seconds", "gpu_mhz", "cpu_prime_mhz",
                       "skin_c", "gpu_c", "cpu_c", "thermal_status")]
            payload = " ".join(str(value if value is not None else -999) for value in values)
            if not all(math.isfinite(float(value)) for value in payload.split()):
                raise RuntimeError("Non-finite hardware sample")
            command = "printf '%s\\n' '" + payload + "' > files/user/.android-hardware.pending && " \
                      "mv files/user/.android-hardware.pending files/user/android-hardware.txt"
            result = read("shell", "run-as", "org.nhllegacy.port", "sh", "-c", '"' + command + '"')
            if result.returncode: raise RuntimeError(result.stderr.strip())
            delay = min(args.interval, args.seconds - (time.monotonic() - started))
            if delay > 0: time.sleep(delay)
    finally:
        if rows:
            with (folder / (args.label + "-hardware.csv")).open("w", newline="", encoding="utf-8") as output:
                writer = csv.DictWriter(output, fieldnames=list(rows[0]))
                writer.writeheader(); writer.writerows(rows)
        (folder / (args.label + "-thermal.txt")).write_text(last_thermal, encoding="utf-8")
    print(json.dumps({"samples": len(rows), "gpu_frequency_available": gpu_available,
                      "latest": rows[-1] if rows else None}, indent=2))

if __name__ == "__main__":
    main()
