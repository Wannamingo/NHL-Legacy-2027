# Team roster updates

The NHL Legacy 2027 launcher imports **Xbox 360 NHL Legacy Edition** roster files from ZIP archives. This is separate from **Import ISO** for game data. A roster is installed for one game profile, then selected in the game's own menus for a new match or season.

## Included community archive

As of **14 October 2026**, the latest archive we found from `devils17` in the **NHL LEGACY EDGE (Xbox 360 + PS3) 2026–27 Roster** thread is [`ROSTER 20261007011552.zip`](../rosters/ROSTER%2020261007011552.zip). The author describes V5, dated **6 October 2026**, as updating transactions, equipment and jersey numbers. See the [original post and later discussion](https://forums.operationsports.com/forums/forum/hockey/ea-sports-nhl/ea-sports-nhl-legacy/ea-sports-nhl-last-gen-rosters/26936547-nhl-legacy-edge-xbox-360-ps3-2026-27-roster/page3).

This is the **Xbox roster**, not the PS3 `SYS-DATA` file. It is provided separately from the APK and ISO. Credit for the roster belongs to `devils17`; the author's publication date, rather than this Android port's release date, indicates how current it is. Check the source thread for newer versions.

The archive contains the payload `ROSTER 20261007011552` (**2,456,120 bytes**) and a macOS metadata entry `__MACOSX/._ROSTER 20261007011552`, which the importer skips. Payload SHA-256: `acb56eee91e8e6c0944ef33fee2ba85c7fc3f556996541d8256d194700868216`.

## Where to find other rosters

- The [NHL LEGACY EDGE 2026–27 Operation Sports thread](https://forums.operationsports.com/forums/forum/hockey/ea-sports-nhl/ea-sports-nhl-legacy/ea-sports-nhl-last-gen-rosters/26936547-nhl-legacy-edge-xbox-360-ps3-2026-27-roster) is the source of the included archive. When both `ROSTER … .zip` and `SYS-DATA.zip` are attached, choose the **`ROSTER … .zip`** Xbox version.
- The [NHL Legacy 25/26 Rosters Xbox 360 Operation Sports thread](https://forums.operationsports.com/forums/forum/hockey/ea-sports-nhl/ea-sports-nhl-legacy/ea-sports-nhl-last-gen-rosters/26840473-nhl-legacy-25-26-rosters-xbox-360) has other community updates. Some downloads have a different outer ZIP layout and need repacking as described below.
- Your own Xbox 360 NHL Legacy Edition roster save, provided you can obtain its inner `RosterFile`. The importer does not read an Xbox 360 save container directly.

Check each archive's game, platform, publication date and actual contents. A filename alone does not prove compatibility. PS3 `SYS-DATA`, unrelated NHL releases, raw `.db`, `.csv/.xlsx`, uniform mods, Xbox save containers and executables cannot be imported directly. Structural validation does not verify a community roster's player assignments, ratings or visual asset compatibility.

## Required ZIP format

The simplest archive has **one roster payload at its root**:

```text
My-roster.zip
└── ROSTER 20261007011552
```

The inner filename can differ. `ROSTER ` followed by 14 digits (`YYYYMMDDhhmmss`) is convenient for the in-game save name. The outer ZIP filename is not used to identify the payload.

| Check | Requirement |
| --- | --- |
| ZIP | At most **12 MiB** (`12,582,912` bytes) and **16 entries** |
| Entries | Exactly **one** roster file at the root; optional root-level `roster.properties` |
| Entry name | At most 64 characters; no `/`, `\`, `:`, `..` or leading dot |
| Roster payload | **64 bytes** to **8 MiB** (`8,388,608` bytes), starting with ASCII `RosterFile` |
| Inner database | Inflates to a `DB` database with the expected table structure; at most **24 MiB** (`25,165,824` bytes) |

Directories and other files are rejected. The importer ignores macOS `__MACOSX/…` and `.DS_Store` metadata, but there is no reason to add them. Renaming a PS3 `SYS-DATA`, a `.db` or another file does **not** convert its format.

An optional UTF-8 `roster.properties` can label the roster and verify its checksum:

```properties
format=NHL-LEGACY-ROSTER-1
payload=ROSTER 20261007011552
sha256=<SHA-256 of the inner roster payload, 64 hex digits>
label=Roster 2026-27
rosterDate=2026-10-06
```

`payload` must match the inner ZIP entry exactly; `sha256` is calculated over the inner file, **not** the ZIP. `label` and `rosterDate` are optional; the date uses `YYYY-MM-DD`. If you do not need metadata, omit `roster.properties`. The original archive above works without it.

## Preparing your own ZIP

1. Confirm that the file is an **Xbox 360 NHL Legacy Edition** roster and its first ten bytes are ASCII `RosterFile`. Do not include the surrounding save folder or `.header`: the launcher creates a save entry for the selected profile.
2. Put that file alone at the root of a new ZIP. If a download contains multiple platform versions or extra documents, extract the Xbox roster payload and package it separately.
3. Copy the ZIP to the Android device and select the ZIP itself in the launcher.
4. Keep an external copy of a previous roster **before** importing another one if you want to retain it. The launcher has no version history or rollback control.

On Windows, File Explorer or 7-Zip can show the ZIP entries. PowerShell's `Get-FileHash -Algorithm SHA256 "ROSTER 20261007011552"` calculates the checksum of an extracted payload. This is a preparation aid, not an Android requirement.

## Importing and using the roster

1. Install the APK, [import your own ISO](../README.en.md#installation), and launch the game once to create a profile if needed.
2. Fully close the game. In the launcher open **Settings → Team rosters**. With multiple profiles, use **Choose roster profile**; with only one, it is selected automatically.
3. Tap **Update roster from ZIP** and select the archive. **Selecting it immediately starts validation and installation**; there is no second confirmation. Keep the launcher open until it reports success or an error.
4. Start the game, select the installed roster in the game's save/load roster menu, then begin a new match or season. Importing a ZIP does not switch the active roster inside an already running game.

The next ZIP for the same profile replaces **only rosters previously installed by this launcher**. Rosters created and saved in-game, other profile files and existing seasons are not overwritten. The launcher keeps no persistent backup or rollback action. An existing season is not updated retroactively; use the selected roster for new matches and seasons.

If an import fails, inspect the platform, inner filename, entry count, sizes and optional `roster.properties`. “Only Xbox RosterFile is supported” means the payload is PS3 or another format. An invalid database error means the payload is corrupt or does not match the supported Xbox database layout; repacking the ZIP cannot fix that.

These rules follow the [current importer implementation](../source/android-final/RosterImporter.java). Community rosters are not embedded in the APK or game ISO.
