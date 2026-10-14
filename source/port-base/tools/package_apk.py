"""Package the actual recompiled ARM64 libraries using the existing DOAX tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_TOOLS = Path("C:/Users/user/Documents/Codex/2026-10-02/new-chat/work/toolchains")

def run(*args):
    subprocess.run(list(map(str, args)), check=True)

def digest(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()

def check_arm64(path):
    with path.open("rb") as file:
        header = file.read(20)
    if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != 183:
        raise SystemExit(f"Not an ARM64 ELF library: {path}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tools", type=Path, default=Path(os.environ.get("NHL_ANDROID_TOOLS", DEFAULT_TOOLS)))
    parser.add_argument("--java-only", action="store_true", help="Prepare/validate Java and dex before guest compilation finishes")
    args = parser.parse_args()
    tools = args.tools.resolve()
    java = tools / "jdk-21.0.12.1+1/bin"
    sdk = tools / "sdk/android-15"
    android_jar = tools / "sdk/android-35/android.jar"
    android_sdk = ROOT / "private/reference-rexglue-android"
    libraries = {"libmain.so": ROOT / "private/build-arm64/libmain.so",
                 "librexruntime.so": android_sdk / "out/linux-arm64/librexruntime.so"}
    if not args.java_only:
        for library in libraries.values():
            if not library.is_file():
                raise SystemExit(f"Build the game first; missing {library}")
            check_arm64(library)
    work = ROOT / "private/apk"
    work.mkdir(parents=True, exist_ok=True)
    source_libraries = dict(libraries)
    if not args.java_only:
        native = work / "native"
        native.mkdir(exist_ok=True)
        strip = tools / "android-ndk-r30/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
        libraries = {}
        for name, source in source_libraries.items():
            file = native / name
            shutil.copyfile(source, file)
            run(strip, "--strip-unneeded", file)
            libraries[name] = file
    classes = work / "classes"
    classes.mkdir(exist_ok=True)
    sources = sorted((ROOT / "android/java").rglob("*.java"))
    sources += sorted((android_sdk / "thirdparty/sdl3/android-project/app/src/main/java").rglob("*.java"))
    arguments = work / "java-sources.txt"
    arguments.write_text("\n".join('"' + path.as_posix() + '"' for path in sources), encoding="utf-8")
    run(java / "javac.exe", "--release", "8", "-encoding", "UTF-8", "-cp", android_jar,
        "-d", classes, "@" + str(arguments))
    jar = work / "classes.jar"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in classes.rglob("*.class"):
            archive.write(file, file.relative_to(classes).as_posix())
    dex = work / "dex"
    dex.mkdir(exist_ok=True)
    run(java / "java.exe", "-cp", sdk / "lib/d8.jar", "com.android.tools.r8.D8",
        "--min-api", "28", "--lib", android_jar, "--output", dex, jar)
    if args.java_only:
        print("Android Java/dex prepared; no APK created.")
        return
    unsigned = work / "unsigned.apk"
    run(sdk / "aapt2.exe", "link", "--manifest", ROOT / "android/AndroidManifest.xml",
        "-I", android_jar, "-o", unsigned)
    with zipfile.ZipFile(unsigned, "a") as archive:
        for file in dex.glob("*.dex"):
            archive.write(file, file.name, compress_type=zipfile.ZIP_DEFLATED)
        for name, file in libraries.items():
            archive.write(file, "lib/arm64-v8a/" + name, compress_type=zipfile.ZIP_STORED)
    aligned = work / "aligned.apk"
    run(sdk / "zipalign.exe", "-P", "16", "-f", "4", unsigned, aligned)
    key = ROOT / "private/nhl-development.jks"
    if not key.exists():
        run(java / "keytool.exe", "-genkeypair", "-keystore", key, "-storepass", "android",
            "-keypass", "android", "-alias", "nhl-local", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000", "-dname", "CN=NHL Legacy Local Development")
    apk = work / "NHL-Legacy-RG477M-arm64-dev.apk"
    signer = [java / "java.exe", "-jar", sdk / "lib/apksigner.jar"]
    run(*signer, "sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--out", apk, aligned)
    run(*signer, "verify", "--verbose", apk)
    run(sdk / "zipalign.exe", "-c", "-P", "16", "4", apk)
    receipt = {"apk": str(apk), "sha256": digest(apk), "bytes": apk.stat().st_size,
               "package": "org.nhllegacy.port", "abi": "arm64-v8a", "version": "0.1.0-dev",
               "libraries": {name: digest(file) for name, file in libraries.items()},
               "unstripped_libraries": {name: digest(file) for name, file in source_libraries.items()},
               "game_assets_included": False, "gameplay_verified": False}
    (work / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(apk)

if __name__ == "__main__":
    main()
