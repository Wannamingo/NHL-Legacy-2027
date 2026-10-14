"""Diagnostic draw bisection on a saved frame; suppressed images are not optimizations.

The game must be closed. Each variant uses its own process and shader cache,
preserves resolves/clears, and never changes the game's renderer settings.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--serial", default="RG477M01025672")
    parser.add_argument("--label", required=True)
    parser.add_argument("--iterations", type=int, default=30)
    parser.add_argument("--ranges", nargs="+", required=True, help="Half-open draw ranges, FIRST:LAST")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label) or not 2 <= args.iterations <= 120:
        raise SystemExit("Invalid label or sample count")
    ranges = []
    for value in args.ranges:
        if not re.fullmatch(r"[0-9]+:[0-9]+", value):
            raise SystemExit("Use FIRST:LAST ranges")
        first, last = map(int, value.split(":"))
        if not 0 <= first < last <= 100000:
            raise SystemExit("Invalid draw range")
        ranges.append((first, last))
    folder = ROOT / "private/probes/replay"
    folder.mkdir(parents=True, exist_ok=True)
    config = folder / (args.label + "-renderer.toml")
    config.write_text("vulkan_readback_resolve_max_length = 3000000\n", encoding="utf-8")
    results = []
    baseline = None
    variants = [("baseline", -1, -1)] + [(f"skip-{a}-{b}", a, b) for a, b in ranges]
    variants.append(("baseline-repeat", -1, -1))
    for index, (variant, first, last) in enumerate(variants):
        name = args.label + "-" + variant
        command = [sys.executable, str(ROOT / "tools/device_isolated_replay.py"),
                   "--serial", args.serial,
                   "--trace", str(args.trace), "--name", name, "--config", str(config),
                   "--iterations", str(args.iterations), "--resident", "--first", str(first), "--last", str(last)]
        if index == 0:
            command.append("--stage")
        print("Testing", variant, flush=True)
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
        log = (folder / (name + ".log")).read_text(encoding="utf-8", errors="replace")
        match = re.search(r"Replay samples_ms:([^\n]+)", log)
        samples = [float(x) for x in match.group(1).split()] if match else []
        if len(samples) != args.iterations:
            raise SystemExit("Incomplete timing: " + name)
        with Image.open(folder / (name + ".ppm")) as source:
            frame = source.convert("RGB")
        if baseline is None:
            baseline = frame.copy()
        delta = ImageChops.difference(baseline, frame).tobytes()
        changed = sum(any(delta[i:i + 3]) for i in range(0, len(delta), 3))
        result = {"variant": variant, "skip_first": first, "skip_last": last,
                  "mean_ms": statistics.mean(samples), "median_ms": statistics.median(samples),
                  "samples_ms": samples, "changed_pixels": changed}
        results.append(result)
        print(f"{variant}: {result['mean_ms']:.3f} ms; {changed} changed pixels", flush=True)
    report = {"trace": str(args.trace), "serial": args.serial,
              "trace_sha256": hashlib.sha256(args.trace.read_bytes()).hexdigest(),
              "diagnostic_only": True, "gameplay_fps_claim": False,
              "includes": "input restoration, command processing, completed GPU work",
              "results": results}
    output = folder / (args.label + "-draw-costs.json")
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(output, flush=True)


if __name__ == "__main__":
    main()
