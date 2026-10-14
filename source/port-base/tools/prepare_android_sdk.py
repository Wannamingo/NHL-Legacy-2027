"""Fetch the pinned Android runtime and apply reviewed NHL/ARM64 patches."""
import argparse
from pathlib import Path
import subprocess
import re
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PIN = "edd4344723ecac3ffa18c5dcd2fcc268f468ff9e"
URL = "https://github.com/Buku313/rexglue-skate3-android.git"
MODULES = ["simde", "spdlog", "fmt", "xxHash", "libmspack", "tomlplusplus", "o1heap", "utfcpp",
           "snappy", "cli11", "inja", "vulkan-headers", "vulkan-memory-allocator", "volk", "imgui",
           "glslang", "spirv-headers", "spirv-tools", "FFmpeg", "sdl3"]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    destination = ROOT / "private/reference-rexglue-android"
    def git(*command, capture=False):
        return subprocess.run(["git", "-C", str(destination), *map(str, command)],
            check=True, capture_output=capture, text=capture)
    if not destination.exists():
        if args.verify_only:
            raise SystemExit("Android SDK is not prepared")
        subprocess.run(["git", "clone", "--filter=blob:none", "--branch", "android-arm64", URL,
                        str(destination)], check=True)
        git("checkout", "--detach", PIN)
    if git("rev-parse", "HEAD", capture=True).stdout.strip() != PIN:
        raise SystemExit("Unexpected SDK revision; local changes were not reset")
    if not args.verify_only:
        git("submodule", "update", "--init", "--depth", "1", *["thirdparty/" + n for n in MODULES])
    patches = [ROOT / "sdk/rexglue-codegen-bcctr-tail-fix.patch",
                  ROOT / "android/patches/rexglue-rg477m-core.patch",
                  ROOT / "android/patches/rexglue-nhl-vulkan-android.patch",
                  ROOT / "android/patches/rexglue-display-overlay.patch",
                  ROOT / "android/patches/rexglue-gamepad-deduplicate.patch",
                  ROOT / "android/patches/rexglue-original-aspect.patch",
                  ROOT / "android/patches/rexglue-fps-stall.patch",
                  ROOT / "android/patches/rexglue-frame-samples.patch",
                  ROOT / "android/patches/rexglue-cache-root-compat.patch",
                  ROOT / "android/patches/rexglue-low-video-mode.patch",
                  ROOT / "android/patches/rexglue-frame-limit.patch",
                  ROOT / "android/patches/rexglue-proc-maps-reader.patch",
                  ROOT / "android/patches/rexglue-page-validity-refresh.patch",
                  ROOT / "android/patches/rexglue-android-cache-read-position.patch",
                  ROOT / "android/patches/rexglue-vulkan-headless-frame.patch",
                  ROOT / "android/patches/rexglue-vulkan-driver-cache.patch",
                  ROOT / "android/patches/rexglue-write-watch-fast-path.patch",
                  ROOT / "android/patches/rexglue-frame-diagnostics.patch",
                  ROOT / "android/patches/rexglue-android-present-queue.patch"]
    command = ["git", "-C", str(destination), "apply", "--ignore-space-change"]
    # Later patches may modify earlier hunks. Check the complete ordered stack
    # in reverse, without changing the SDK, rather than checking each in isolation.
    installed_count = 0
    def sequential_reverse_check(stack):
        # git apply --check with several overlapping patches may check a later
        # hunk against the original preimage. Verify in order in a disposable
        # copy, never by reverting the working SDK.
        paths = {m for patch in stack for m in re.findall(r"^\+\+\+ b/(.+)$",
            patch.read_text(encoding="utf-8"), re.MULTILINE)}
        temporary_root = ROOT / "private/build-temp"
        temporary_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as temporary:
            temporary = Path(temporary)
            for path in paths:
                source = destination / path
                if source.is_file():
                    target = temporary / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, target)
            for patch in reversed(stack):
                result = subprocess.run(["git", "-C", str(temporary), "apply",
                    "--no-index", "--ignore-space-change", "--reverse", str(patch)], capture_output=True)
                if result.returncode:
                    return False
            return True
    for count in range(len(patches), 0, -1):
        installed = subprocess.run(command + ["--reverse", "--check",
            *map(str, reversed(patches[:count]))], capture_output=True)
        if installed.returncode == 0 or sequential_reverse_check(patches[:count]):
            installed_count = count
            break
    if args.verify_only and installed_count != len(patches):
        raise SystemExit("SDK patch stack does not match the prepared source")
    for patch in patches[:installed_count]:
        print("Verified", patch.name)
    for patch in patches[installed_count:]:
        subprocess.run(command + ["--check", str(patch)], check=True)
        subprocess.run(command + [str(patch)], check=True)
        print("Applied", patch.name)

if __name__ == "__main__":
    main()
