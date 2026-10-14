"""Print retail guest assembly comments for a generated function."""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("addresses", nargs="+")
parser.add_argument("--callers", action="store_true", help="Print direct callers instead of function bodies")
args = parser.parse_args()
wanted = {"sub_" + value.removeprefix("sub_").removeprefix("0x").upper() for value in args.addresses}
for path in (ROOT / "generated/default").glob("nhllegacy_recomp.*.cpp"):
    content = path.read_text(encoding="utf-8")
    for match in re.finditer(r"DEFINE_REX_FUNC\((sub_[0-9A-F]+)\) \{(.*?)(?=\nDEFINE_REX_FUNC|\Z)", content, re.S):
        if args.callers:
            called = sorted(name for name in wanted if name + "(ctx, base)" in match[2])
            if called:
                print(f"{match[1]} ({path.name}): " + ", ".join(called))
            continue
        if match[1] not in wanted:
            continue
        print(f"{match[1]} ({path.name})")
        print("\n".join(line.strip() for line in match[2].splitlines()
                        if line.lstrip().startswith("//") or line.startswith("loc_")))
        wanted.remove(match[1])
if wanted and not args.callers:
    raise SystemExit("Functions not found: " + ", ".join(sorted(wanted)))
