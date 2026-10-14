"""Summarize bounded renderer timestamp traces; spans may include queue idle time."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument("log", type=Path)
parser.add_argument("--output", type=Path)
args = parser.parse_args()
frames = []
for line in args.log.read_text(encoding="utf-8", errors="replace").splitlines():
    head = re.search(r"GPU timestamp trace: frame=(\d+).*span_us=(\d+) intervals=(\d+)", line)
    if head:
        frames.append({"frame": int(head[1]), "span_us": int(head[2]), "intervals": []})
        continue
    item = re.search(r"\[(\d+)\] (\w+) start=(-?\d+) end=(-?\d+) dur=(\d+)(.*)", line)
    if item and frames:
        frames[-1]["intervals"].append({"index": int(item[1]), "bucket": item[2],
            "start_us": int(item[3]), "end_us": int(item[4]), "duration_us": int(item[5]),
            "available": "UNAVAILABLE" not in item[6]})
for frame in frames:
    totals = defaultdict(int)
    valid = [item for item in frame["intervals"] if item["available"]]
    for item in valid:
        totals[item["bucket"]] += item["duration_us"]
    frame["totals_us"] = dict(sorted(totals.items(), key=lambda pair: -pair[1]))
    frame["largest_intervals"] = sorted(valid, key=lambda item: -item["duration_us"])[:12]
    frame["unattributed_us"] = frame["span_us"] - sum(totals.values())
    print(f"Frame {frame['frame']}: {frame['span_us']} us, remaining {frame['unattributed_us']} us")
    print(" ".join(f"{key}={value}" for key, value in frame["totals_us"].items()))
    print("largest:", " ".join(f"{item['index']}:{item['bucket']}={item['duration_us']}"
          for item in frame["largest_intervals"][:6]))
if args.output:
    args.output.write_text(json.dumps({"source": str(args.log), "frames": frames,
        "caveat": "Timestamp intervals include barriers, pass stores/loads and possible CPU queue gaps; not isolated shader execution."},
        indent=2) + "\n", encoding="utf-8")
