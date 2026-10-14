"""Inspect the bounded diagnostic packed crowd array (big-endian half records)."""
import argparse
from collections import Counter
from pathlib import Path
import struct

parser = argparse.ArgumentParser()
parser.add_argument("path", type=Path)
args = parser.parse_args()
data = args.path.read_bytes()
if len(data) % 32:
    raise SystemExit("Not an array of 32-byte records")
counts = Counter()
examples = []
for index in range(len(data) // 32):
    raw = data[index * 32:(index + 1) * 32]
    values = struct.unpack_from(">12e", raw)
    x, y, z, angle = values[:4]
    if abs(y - .33081055) < .002 or abs(y - .8305664) < .002:
        counts["lower_bowl"] += 1
        if z == 0:
            counts["lower_bowl_z_zero"] += 1
            if len(examples) < 24:
                examples.append((index, values[:4], raw[:8].hex()))
    if z == 0:
        counts["all_z_zero"] += 1
print("records", len(data) // 32, dict(counts))
for item in examples:
    print(item)
