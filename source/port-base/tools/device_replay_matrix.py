"""Compare renderer variants on one captured frame; never alter the game's config."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
CASES = {
    "baseline": {},
    "clear-cutout": {"vulkan_retail_clear_transfer_cutout": True},
    "same-constants": {"vulkan_skip_identical_alu_constants": True},
    "prime-cpu": {"android_thread_placement_map": "GPU Commands=cpu:7"},
    "big-cpu": {"android_thread_placement_map": "GPU Commands=cpu:2-7"},
    "inert": {"vulkan_skip_inert_no_pixel_draws": True},
    "noocclusion": {"occlusion_query_enable": False},
    "game-readback": {"vulkan_readback_resolve_max_length": 3000000},
    "game-readback-repeat": {"vulkan_readback_resolve_max_length": 3000000},
    "readback-2m": {"vulkan_readback_resolve_max_length": 2000000},
    "readback-1m": {"vulkan_readback_resolve_max_length": 1000000},
    "readback-256k": {"vulkan_readback_resolve_max_length": 262144},
    "readback-disabled": {"vulkan_readback_resolve": False},
    "native2x": {"native_2x_msaa": True},
    "renderpass": {"vulkan_dynamic_rendering": False},
    "direct": {"vulkan_direct_resolve_fast32": True},
    "deferred": {"vulkan_deferred_resolve_clears": True},
    "reuse": {"vulkan_reuse_transient_texture_descriptors": True,
              "vulkan_reuse_unchanged_draw_bindings": True},
    "push": {"vulkan_push_constants_descriptors": True},
    "push-reuse": {"vulkan_push_constants_descriptors": True,
                   "vulkan_reuse_transient_texture_descriptors": True,
                   "vulkan_reuse_unchanged_draw_bindings": True},
    "combined": {"vulkan_direct_resolve_fast32": True,
                 "vulkan_deferred_resolve_clears": True,
                 "vulkan_reuse_transient_texture_descriptors": True,
                 "vulkan_reuse_unchanged_draw_bindings": True},
    "baseline-repeat": {},
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--serial", default="RG477M01025672")
    parser.add_argument("--trace", type=Path, default=ROOT / "private/probes/454109EC_79726.xtr")
    parser.add_argument("--iterations", type=int, default=30)
    parser.add_argument("--max-readback-bytes", type=int,
                        help="Use the same CPU readback limit for every variant")
    parser.add_argument("--resident", action="store_true")
    parser.add_argument("--isolate", action="store_true", help="Require NHL's game process to be closed before each test")
    parser.add_argument("--cases", nargs="+", choices=list(CASES),
                        default=["baseline", "renderpass", "direct", "deferred",
                                 "reuse", "combined", "baseline-repeat"])
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label) or not 2 <= args.iterations <= 120:
        raise SystemExit("Use a safe label and 2..120 iterations")
    folder = ROOT / "private/probes/replay-configs"
    folder.mkdir(parents=True, exist_ok=True)
    results = []
    baseline = None
    for index, case in enumerate(args.cases):
        values = {} if args.max_readback_bytes is None else {
            "vulkan_readback_resolve_max_length": args.max_readback_bytes}
        values.update(CASES[case])
        config = folder / (args.label + "-" + case + ".toml")
        config.write_text("".join(f"{key} = {json.dumps(value)}\n" for key, value in values.items()), encoding="utf-8")
        name = args.label + "-" + case
        runner = "device_isolated_replay.py" if args.isolate else "device_replay.py"
        command = [sys.executable, str(ROOT / "tools" / runner), "--trace", str(args.trace),
                   "--name", name, "--serial", args.serial,
                   "--iterations", str(args.iterations), "--config", str(config)]
        if args.resident:
            command.append("--resident")
        if index == 0:
            command.append("--stage")
        print("Testing", case, flush=True)
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
        log = (ROOT / "private/probes/replay" / (name + ".log")).read_text(encoding="utf-8", errors="replace")
        samples = re.search(r"Replay samples_ms:([^\n]+)", log)
        if not samples:
            raise SystemExit("Missing completed-GPU timing in " + name)
        times = [float(value) for value in samples.group(1).split()]
        if len(times) != args.iterations:
            raise SystemExit("Incomplete timing in " + name)
        ppm = ROOT / "private/probes/replay" / (name + ".ppm")
        with Image.open(ppm) as source:
            image = source.convert("RGB")
        if baseline is None:
            baseline = image.copy()
        difference = ImageChops.difference(baseline, image)
        raw = difference.tobytes()
        changed = sum(any(raw[i:i+3]) for i in range(0, len(raw), 3))
        result = {"case": case, "config": values, "samples": times, "mean_ms": sum(times)/len(times),
                  "median_ms": sorted(times)[len(times)//2], "max_ms": max(times),
                  "changed_pixels": changed, "image_sha256": hashlib.sha256(ppm.read_bytes()).hexdigest()}
        results.append(result)
        print(f"{case}: {result['mean_ms']:.3f} ms; {changed} changed pixels", flush=True)
    report = {"serial": args.serial,
              "trace_sha256": hashlib.sha256(args.trace.read_bytes()).hexdigest(),
              "readback": "fast", "quality": "native 1280x720, captured settings",
              "includes": "captured input restoration, command processing and completed GPU work",
              "memory_mode": "retain unchanged inputs" if args.resident else "restore every input",
              "excludes": "file loading, first warmup, image export, guest game threads",
              "results": results}
    output = ROOT / "private/probes/replay" / (args.label + "-matrix.json")
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(output, flush=True)

if __name__ == "__main__":
    main()
