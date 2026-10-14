"""Stage optional live render diagnostics in NHL's private device storage."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="RG477M01025672")
    parser.add_argument("--readback", choices=["full", "fast", "some", "none", "auto"], default="full")
    parser.add_argument("--max-readback-bytes", type=int, default=3000000)
    parser.add_argument("--capture", action="store_true")
    parser.add_argument("--crowd-diagnostics", action="store_true")
    parser.add_argument("--benchmark", action="store_true")
    parser.add_argument("--frame-time-hud", action=argparse.BooleanOptionalAction, default=None)
    parser.add_argument("--frame-limit", type=int, choices=[0, 60], default=None,
                        help="Bounded pacing comparison; restore 60 after the test")
    parser.add_argument("--separate-present-queue", action=argparse.BooleanOptionalAction, default=None,
                        help="Opt-in Android same-family presentation queue comparison")
    parser.add_argument("--cpu-profile", action=argparse.BooleanOptionalAction, default=None,
                        help="Requires a diagnostic SDK built with --perf-counters")
    parser.add_argument("--fast-write-watch", action="store_true")
    parser.add_argument("--native-vp6", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--fence-wait-us", type=int, choices=[0, 50, 100, 250, 500, 1000], default=0)
    parser.add_argument("--fence-pending-backoff-us", type=int, choices=[0, 50, 100, 250],
                        default=None, help="Requires the bounded frame-fence backoff test build")
    parser.add_argument("--gpu-placement", choices=["all", "big", "prime"],
                        help="Live GPU worker placement; all restores all eight Y700 cores")
    parser.add_argument("--shadows", choices=["native", "filtered", "soft"])
    parser.add_argument("--reuse-bindings", action="store_true")
    parser.add_argument("--no-occlusion", action="store_true")
    parser.add_argument("--quiet", action="store_true", help="Disable timing and verbose GPU logging")
    parser.add_argument("--gpu-timestamp-buckets", action=argparse.BooleanOptionalAction,
                        default=None, help="GPU category timestamps independently of verbose logging")
    parser.add_argument("--gpu-timestamp-trace-frames", type=int, choices=range(6), default=0,
                        help="Bounded detailed timestamp trace (0..5 completed frames)")
    args = parser.parse_args()
    settings = {
        "nhl_render_diagnostics": not args.quiet,
        "nhl_capture_gpu_frame": args.capture,
        "nhl_crowd_diagnostics": args.crowd_diagnostics,
        "nhl_benchmark_frames": args.benchmark,
        "memory_write_watch_fast_path": args.fast_write_watch,
        "nhl_native_vp6": args.native_vp6,
        "nhl_fence_wait_us": args.fence_wait_us,
        "readback_resolve": args.readback,
        "vulkan_readback_resolve": args.readback != "none",
        "vulkan_readback_resolve_max_length": args.max_readback_bytes,
        "vulkan_gpu_timestamp_buckets": not args.quiet,
        "vulkan_gpu_timestamp_trace_frames": args.gpu_timestamp_trace_frames,
        "vulkan_debug_log_frame_summaries_remaining": 0 if args.quiet else 36000,
        "vulkan_debug_frame_summary_interval_frames": 120,
        "vulkan_reuse_transient_texture_descriptors": args.reuse_bindings,
        "vulkan_reuse_unchanged_draw_bindings": args.reuse_bindings,
        "occlusion_query_enable": not args.no_occlusion,
        "trace_gpu_prefix": "/data/user/0/org.nhllegacy.port/files/user/gpu-trace/",
    }
    if args.shadows is not None:
        settings["shadow_filter_linear"] = args.shadows != "native"
        settings["shadow_softness"] = 2 if args.shadows == "soft" else 0
    if args.gpu_timestamp_buckets is not None:
        settings["vulkan_gpu_timestamp_buckets"] = args.gpu_timestamp_buckets
    if args.cpu_profile is not None:
        settings["gpu_cpu_profile"] = args.cpu_profile
    if args.frame_time_hud is not None:
        settings["nhl_frame_time_hud"] = args.frame_time_hud
    if args.frame_limit is not None:
        settings["guest_frame_limit"] = args.frame_limit
    if args.separate_present_queue is not None:
        settings["vulkan_separate_present_queue"] = args.separate_present_queue
    if args.fence_pending_backoff_us is not None:
        settings["nhl_fence_pending_backoff_us"] = args.fence_pending_backoff_us
    if args.gpu_placement is not None:
        settings["android_thread_placement_map"] = "GPU Commands=cpu:" + {
            "all": "0-7", "big": "2-7", "prime": "7"}[args.gpu_placement]
    source = ROOT / "private/probes/android-renderer.toml"
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text("\n".join(f"{key} = {json.dumps(value)}" for key, value in settings.items())
                      + "\n", encoding="utf-8")
    adb = ["C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains/platform-tools/adb.exe",
           "-s", args.serial]
    def run(*command):
        subprocess.run(adb + list(map(str, command)), check=True)
    temporary = "/data/local/tmp/nhllegacy-port-tests/renderer-settings.toml"
    run("shell", "mkdir", "-p", "/data/local/tmp/nhllegacy-port-tests")
    run("push", source, temporary)
    run("shell", "run-as", "org.nhllegacy.port", "mkdir", "-p", "files/user")
    run("shell", "run-as", "org.nhllegacy.port", "cp", temporary,
        "files/user/.android-renderer.toml.pending")
    run("shell", "run-as", "org.nhllegacy.port", "mv", "files/user/.android-renderer.toml.pending",
        "files/user/android-renderer.toml")
    print("Live render settings:", args.readback, "reuse:", args.reuse_bindings,
          "occlusion:", not args.no_occlusion, "capture:", args.capture)

if __name__ == "__main__":
    main()
