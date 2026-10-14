# NHL Legacy 2027 for Android

An unofficial Android ARM64 port of NHL Legacy Edition. Release `v1.0.1` includes the NHL Legacy 2027 launcher, Vulkan renderer, touch controls, and import from your own Xbox 360 ISO.

**[Download APK](https://github.com/Wannamingo/NHL-Legacy-2027/releases/download/v1.0.1/NHL-Legacy-2027.apk)** · [Releases](https://github.com/Wannamingo/NHL-Legacy-2027/releases) · [Русский](README.md)

## Installation

1. Install `NHL-Legacy-2027.apk` on an ARM64 Android device.
2. Copy your own **NHL Legacy Edition (USA, Europe) (En,Fr,De,Sv,Fi,Ru,Cs)** ISO to the device. The verified Redump image is **8,738,846,720 bytes**.
3. Select **Import ISO** in the launcher. The app extracts the 20 required files (about **6.3 GB**) into app storage and verifies their sizes and SHA-256 hashes. Keep enough free space for the extracted files. The image can be removed from the device after a successful import.
4. Launch the game. The system Vulkan driver and full arena are selected by default.

ZIP import for **game data** has been removed. ZIP remains available separately for roster updates and optional AdrenoTools Vulkan drivers. The release does not include an ISO, game data, or roster updates.

## Features

- Match quality modes: 960 × 540 or 1280 × 720; UI at 1280 × 720. Choose the mode in the launcher before starting the game.
- Independent visibility controls for spectators, stands and seats, and bench players. On-ice player quality is retained.
- System Vulkan driver or a user-imported AdrenoTools driver; driver changes require restarting the game. A graphics-cache clear option is available.
- Per-profile roster ZIP updates for new games and seasons without rewriting existing saves.
- In-game control settings via the gear beside FPS or Select → Settings. Live render-resolution changes are disabled.

## Validation and limits

The user completed an ISO import on a Lenovo Y700 (`TB321FU`, Android 15). All 20 files were also verified against the image on a computer by SHA-256. Importer tests cover failure, cancellation, commit, cache preservation and save preservation.

Full-game completion and every GPU/driver combination are untested. FPS can drop in demanding views, and goalkeeper pose errors have previously appeared in replays. An optional Turnip driver may perform worse than the system driver. Adreno 830 and other GPUs need separate device testing.

## Source status

[`source/`](source/README.md) contains selected Android and port sources, patches, and late-stage native reference fragments. It is **not a complete reproducible source tree for the APK**: game functions are generated from the user's own game copy, and some late native changes exist only as reference fragments. Historical build scripts in the base tree do not build this release unmodified.

The game ISO/XEX, extracted resources, generated game code, saves, GPU caches, and signing key are excluded. This project is unaffiliated with EA Sports and NHL. Game names and artwork belong to their respective owners. See [third-party components](THIRD_PARTY_NOTICES.md).
