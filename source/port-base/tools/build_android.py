"""Local Android build; reuses the user's DOAX toolchain without modifying it."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_TOOLS = Path("C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains")

def run_logged(command, name, env=None):
    log = ROOT / "private" / (name + ".log")
    log.parent.mkdir(parents=True, exist_ok=True)
    print(name, flush=True)
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(list(map(str, command)), env=env, stdout=output, stderr=subprocess.STDOUT)
    print("\n".join(log.read_text(errors="replace").splitlines()[-22:]), flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tools", type=Path, default=Path(os.environ.get("NHL_ANDROID_TOOLS", DEFAULT_TOOLS)))
    parser.add_argument("--host-codegen", action="store_true")
    parser.add_argument("--sdk", action="store_true")
    parser.add_argument("--perf-counters", action="store_true",
                        help="Diagnostic SDK build with CPU attribution counters; off by default")
    parser.add_argument("--game", action="store_true")
    parser.add_argument("--configure-only", action="store_true")
    parser.add_argument("--parallel", type=int, default=4)
    parser.add_argument("targets", nargs="*")
    args = parser.parse_args()
    if args.perf_counters and not args.sdk:
        parser.error("--perf-counters requires --sdk")
    tools = args.tools.resolve()
    cmake = tools / "cmake/bin/cmake.exe"
    ninja = tools / "ninja/ninja.exe"
    llvm = tools / "android-ndk-r30/toolchains/llvm/prebuilt/windows-x86_64/bin"
    env = dict(os.environ)
    temporary = ROOT / "private/build-temp"
    temporary.mkdir(parents=True, exist_ok=True)
    env["TMP"] = str(temporary)
    env["TEMP"] = str(temporary)
    if args.host_codegen:
        # Reuse the known local MSVC/SDK installation. VsDevCmd fails in this
        # environment; passing INCLUDE/LIB directly avoids changing the system.
        vc = Path("C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207")
        kits = Path("C:/Program Files (x86)/Windows Kits/10")
        env["INCLUDE"] = ";".join(map(str, [vc / "include"] + [kits / "Include/10.0.26100.0" / n for n in ("ucrt", "shared", "um")]))
        env["LIB"] = ";".join(map(str, [vc / "lib/x64", kits / "Lib/10.0.26100.0/ucrt/x64", kits / "Lib/10.0.26100.0/um/x64"]))
        env["PATH"] = ";".join(map(str, [vc / "bin/Hostx64/x64", kits / "bin/10.0.26100.0/x64", llvm])) + ";" + env["PATH"]
        source, build = ROOT / "android/host-codegen", ROOT / "private/build-host-codegen"
        flags = ["-DCMAKE_C_COMPILER=" + str(llvm / "clang-cl.exe"), "-DCMAKE_CXX_COMPILER=" + str(llvm / "clang-cl.exe")]
        targets = args.targets or ["rexglue"]
    else:
        source = ROOT / ("private/reference-rexglue-android" if args.sdk else "android")
        build = ROOT / ("private/build-sdk-arm64" if args.sdk else "private/build-arm64")
        flags = ["-DCMAKE_TOOLCHAIN_FILE=" + str(tools / "android-ndk-r30/build/cmake/android.toolchain.cmake"),
                 "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-28"]
        if args.sdk:
            flags += ["-DREXGLUE_USE_VULKAN=ON", "-DREXGLUE_USE_D3D12=OFF", "-DREXGLUE_ENABLE_FIDELITYFX=OFF",
                      "-DREXGLUE_ENABLE_TRACY=OFF",
                      "-DREXGLUE_ENABLE_PERF_COUNTERS=" + ("ON" if args.perf_counters else "OFF"),
                      "-DREXGLUE_BUILD_TESTS=OFF",
                      "-DCMAKE_CXX_FLAGS=-D_LIBCPP_ENABLE_EXPERIMENTAL", "-DPython3_EXECUTABLE=" + sys.executable,
                      "-DPYTHON_EXECUTABLE=" + sys.executable]
        else:
            flags += ["-DNHL_BUILD_GAME=" + ("ON" if args.game else "OFF")]
        targets = args.targets or (["rexruntime"] if args.sdk else (["main"] if args.game else ["nhl_device_probe", "nhl_fiber_test"]))
    run_logged([cmake, "-S", source, "-B", build, "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=" + str(ninja),
                "-DCMAKE_BUILD_TYPE=Release", *flags], build.name + "-configure", env)
    if not args.configure_only:
        if not 1 <= args.parallel <= 16:
            raise SystemExit("--parallel must be between 1 and 16")
        run_logged([cmake, "--build", build, "--target", *targets, "--parallel", str(args.parallel)], build.name + "-build", env)

if __name__ == "__main__":
    main()
