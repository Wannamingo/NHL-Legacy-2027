"""Generate private build inputs from the user's local NHL installation."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[1]
SUPPORTED_XEX = "d818eb15775c1fee34b057b0fc494b211535d07123230ee45bd7d40991c55624"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--game", type=Path, default=Path("D:/Games/NHL Legacy/game"))
    args = parser.parse_args()
    game = args.game.resolve()
    image = game / "default.xex"
    raw = image.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if raw[:4] != b"XEX2" or digest != SUPPORTED_XEX:
        raise SystemExit("default.xex does not match the local NHL input checked for this port")
    header_count = struct.unpack_from(">I", raw, 20)[0]
    title_id = None
    for index in range(header_count):
        key, offset = struct.unpack_from(">II", raw, 24 + index * 8)
        if key == 0x40006:
            title_id = f"{struct.unpack_from('>I', raw, offset + 12)[0]:08x}"
    private = ROOT / "private"
    private.mkdir(exist_ok=True)
    original = (ROOT / "nhllegacy_manifest.toml").read_text(encoding="utf-8")
    original = re.sub(r'(?m)^game_root = .*$', "game_root = " + json.dumps(game.as_posix()), original)
    original = re.sub(r'(?m)^file_path = .*$', "file_path = " + json.dumps(image.as_posix()), original)
    original = re.sub(r'(?m)^out_directory_path = .*$', "out_directory_path = " + json.dumps((ROOT / "generated/default").as_posix()), original)
    includes = [ROOT / "nhllegacy_functions.toml", ROOT / "android/renderer_hooks.toml"]
    original = re.sub(r'(?m)^includes = .*$', "includes = " + json.dumps([p.as_posix() for p in includes]), original)
    (private / "nhllegacy_manifest.toml").write_text(original, encoding="utf-8")
    files = [path for path in game.rglob("*") if path.is_file() and "$SystemUpdate" not in path.parts]
    receipt = {"status": "input-verified-not-a-game-build", "game_root": str(game),
               "xex_sha256": digest, "xex_bytes": len(raw), "title_id": title_id,
               "data_file_count": len(files), "data_bytes": sum(path.stat().st_size for path in files),
               "upstream_manifest_sha256": hashlib.sha256((ROOT / "nhllegacy_manifest.toml").read_bytes()).hexdigest(),
               "function_declarations_sha256": hashlib.sha256((ROOT / "nhllegacy_functions.toml").read_bytes()).hexdigest()}
    (private / "input-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))

if __name__ == "__main__":
    main()
