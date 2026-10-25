# Terrain Recorder

[简体中文](README.md) | [English](README.en.md)

![icon](src/main/resources/assets/terrainrecorder/icon.png)

A **client-only** Fabric mod for Minecraft Java **1.21.11**. It captures the terrain you have loaded into plain-text snapshots, lets you extend or trim a snapshot while walking around, and highlights every block that differs from a snapshot with **see-through red boxes**.

- Reads only the chunks already loaded in the client — no custom packets, no server-side install
- Works in singleplayer and on any multiplayer server (no OP needed)
- Everything is stored locally; snapshots are plain text and can be edited, backed up or zipped
- Ships with **English / Simplified Chinese / Traditional Chinese**, and groups regional variants automatically (British English, Hong Kong Traditional Chinese, …)

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Key bindings](#key-bindings)
- [Command reference](#command-reference)
- [Languages](#languages)
- [How it works](#how-it-works)
- [Where data is stored](#where-data-is-stored)
- [Import / export / download](#import--export--download)
- [Limitations](#limitations)
- [Building from source](#building-from-source)
- [Project layout](#project-layout)
- [License](#license)

---

## Features

| Feature | Command | Description |
| --- | --- | --- |
| Capture a snapshot | `/rec` `/rc` | Save the currently loaded terrain as a timestamped snapshot |
| Extend the map | `/recmode add` `/rcm a` | Align to an old snapshot, then add newly seen blocks as you walk (marked blue) |
| Trim the map | `/recmode min` `/rcm m` | Align to an old snapshot, then remove vanished blocks as you walk (marked blue) |
| Finish | `/recmode stop` `/rcm s` | Write the result back to the base snapshot |
| Find differences | `/find` `/fd` | Auto-match the best snapshot and outline extra / changed blocks in red |
| Compare a chosen snapshot | `/find <id>` | Compare against one specific snapshot |
| Stop highlighting | `/find off` | Leave find mode |
| Delete snapshots | `/delrec` | Delete by id, delete all, or keep only one |
| Export | `/rec export` `/rcex` | Pack snapshots into a zip (can span servers) |
| Import | `/rec import` `/rcim` | Restore snapshots from a zip, confirming conflicts one by one |
| Download | `/rec download import <url>` | Download a zip in the background → import → delete it |
| Hotkeys | — | Five rebindable actions |
| Languages | — | English / Simplified / Traditional Chinese with variant grouping |

All commands are **client commands**: they run locally and are never sent to the server.

---

## Requirements

| Item | Requirement |
| --- | --- |
| Game version | Minecraft Java **1.21.11** |
| Mod loader | Fabric Loader **≥ 0.19.5** |
| Dependency | Fabric API (built against `0.141.6+1.21.11`) |
| Java | **21** or newer |
| Side | **Client only** (`environment: client`; does nothing on a dedicated server) |
| Tested on | Singleplayer worlds and multiplayer servers (including strict anti-cheat servers, since it sends nothing) |

> To port to a newer version, change `minecraft_version` / `loader_version` / `fabric_api_version` in `gradle.properties` and handle the rendering-pipeline / `Identifier` API changes after 1.21.11.

---

## Installation

1. Install **Fabric Loader ≥ 0.19.5** for 1.21.11 with your launcher.
2. Put these two jars into `.minecraft/mods/`:
   - `fabric-api-0.141.6+1.21.11.jar` (or newer for 1.21.11)
   - `terrainrecorder-1.0.0.jar`
3. Launch the game. Type `/recmode` or `/find` in chat to confirm the mod is loaded.

---

## Key bindings

Defaults can be changed in **Options → Controls → Key Binds → Terrain Recorder** (the category name follows your client language):

| Default key | Action | Equivalent command |
| --- | --- | --- |
| `\` | Capture a snapshot | `/rec` |
| `=` | Start add mode | `/recmode add` |
| `-` | Start remove mode | `/recmode min` |
| `Backspace` | Finish and save | `/recmode stop` |
| `Enter` | Toggle find mode | `/find` → `/find off` |

> Hotkeys only fire in-world with no screen open. `\`, `=` and `-` sit in different places on non-US layouts.
> `Enter` is close to some interface habits; clear the binding if it gets in the way.

---

## Command reference

### `/rec` · `/rc` — capture a snapshot

```
/rec
```

Saves every non-air block in the **currently loaded chunks** as `snapshot_<yyyyMMdd_HHmmss>.snap` in the current server's (or world's) snapshot folder, then prints a clickable file name that reveals it in your file manager.

- The covered area depends on your render/loaded distance, so **where you stand and your view distance change what is captured**.
- Writes go to a `.tmp` file first and then replace the target atomically, so an interrupted save cannot corrupt the previous snapshot.
- Two captures within the same second get `_2`, `_3`, … suffixes instead of overwriting each other.

### `/recmode` · `/rcm` — extend / trim the map

```
/recmode              show help
/recmode add   (/a)   extend: add blocks the map does not have
/recmode min   (/m)   trim: remove blocks that have vanished
/recmode stop  (/s)   finish and write the result back
```

On start the mod picks the best-matching snapshot under your feet (see [How it works](#how-it-works)), then:

- **add** — newly scanned blocks that the map does not have are added and marked with **blue boxes**. Blocks added during this session that are then broken are removed from the map again.
- **min** — blocks that the map has but the world no longer has (air or a different block) are removed and marked with **blue boxes**. If a block is placed back, that removal is undone.
- **stop** — writes the in-memory map back to the **base snapshot file** (overwriting it, no copy is kept). If no snapshot could be aligned, a new timestamped one is created instead.

> ⚠️ `stop` **overwrites** the base snapshot. Export a backup with `/rec export` before editing anything important.

### `/find` · `/fd` — find differences

```
/find          automatically match the best snapshot
/find <id>     compare with a specific snapshot (id is tab-completed)
/find off      stop highlighting
```

A difference means: **the block exists in the world but not in the snapshot (or the block id at that position differs)** → **red box**, drawn without depth test so it shows through terrain.

- Boxes are anchored to world coordinates and stay visible as you walk away.
- Only "extra" blocks are shown; blocks that the snapshot has but the world no longer has are **not** shown.
- **Only chunks the snapshot actually covers are compared**: areas the snapshot never recorded are not treated as "all new".
- While playing, a newly loaded chunk that is **not covered by the snapshot is skipped entirely** (no scanning, no drawing, no block-change handling).
- Enabling `/find` only does three cheap things: a sparse sample (±4 chunks, every 4th block) for alignment, reading the snapshot (fast parser for our own format, ~3–4× faster), and expanding the chunk index that was built while loading.
- **Boxes appear in stages**: at most ~100 new boxes per frame, reaching the cap in about 0.1 s, so the first frame cannot stall.
- At most 800 boxes are drawn per frame; the rest are skipped for that frame to keep the frame rate.
- Only blocks within **10 chunks (~160 blocks)** of the player are drawn or scanned.
- When enabled, chunks are scanned **nearest-first** over several ticks (the action bar shows what is left); after that it costs nothing,
  and later block changes add/remove boxes in real time.
- **More than 5000 differences stops highlighting automatically** and tells you why — usually the snapshot does not cover this area (it was captured elsewhere).
- Dense diffs are capped at 800 boxes; **which 800 are drawn depends only on block positions, never on the view direction** (off-screen boxes are clipped by the GPU), so turning the camera cannot swap the visible set.
- Only blocks within **10 chunks (~160 blocks)** of the player are drawn or scanned.
- When enabled, chunks are scanned **nearest-first** over several ticks (the action bar shows what is left); after that it costs nothing,
  and later block changes add/remove boxes in real time.
- **More than 5000 differences stops highlighting automatically** and tells you why — usually the snapshot does not cover this area (it was captured elsewhere).
- When you compare a specific snapshot and it has no data near you, chat says it cannot be compared instead of flooding the screen.
- When neither the player's chunk nor the surrounding ring is covered by the snapshot, three checks (about 6 seconds) stop `/find` (e.g. after a teleport or a lobby kick).

### `/delrec` — delete snapshots

```
/delrec                  show help
/delrec <id>             delete one snapshot (id is tab-completed)
/delrec all              delete every snapshot in the current server folder
/delrec all else <keep>  delete all but keep (a snapshot id, or latest = newest file)
```

> Find mode is only stopped after a deletion actually succeeds, so a typo cannot silently turn off `/find`.

### `/rec export` · `/rcex` — export

```
/rec export                     show help
/rec export -a                  export every snapshot of this server
/rec export <id> [<id> ...]     export the given snapshots (deduplicated)
/rec export <id...> -o <name>   choose the zip name
/rec export -a-f                export snapshots of all servers (skips the zips folder)
```

- The zip is written to `<terrain_snapshots>/zips/snapshot_<timestamp>.zip`; `-o name` overrides it (`.zip` is appended, illegal characters become `_`).
- An explicitly given id that cannot be found is an **error** — it never falls back to "export everything".
- The zip layout is `<server>/<file>.snap`, so one zip can carry snapshots from several servers.
- The success message contains a clickable file name that reveals the zip in your file manager.

### `/rec import` · `/rcim` — import

```
/rec import                  show help
/rcim -x <zip path>          import a zip from the zips folder (path relative to it, tab-completed)
/rcim -x                     without a path → an "open file" dialog appears
/rcim -x <zip> -f            overwrite existing files without asking
```

- Only `.snap` entries are imported; other files are counted and ignored.
- Without `-f`, each conflict gets clickable chat buttons: **Overwrite / Skip**, plus **Overwrite all remaining / Cancel all**.
- Path validation refuses escapes (at most one `..` back to `terrain_snapshots/`), and the final write is re-checked to stay inside the snapshot root.

### `/rec download import <url>` — download and import

```
/rec download import https://example.com/pack.zip
/rec download import jx-bjd-dmm        # built-in shortcut
/rec -d -i <url>                       # shorter spelling
```

Downloads into `zips/`, runs the normal import flow, and deletes the zip afterwards (after conflict confirmation if any).

- The download runs on a **background thread** with connect/read timeouts and a 512 MB size cap, so a slow network will not freeze the game.

### Internal / hidden commands

These are triggered by **clickable chat text** and normally never typed:

| Command | Trigger | Purpose |
| --- | --- | --- |
| `/openrec <id>` | Click a snapshot file name after `/rec` | Reveal the snapshot in the file manager |
| `/trzip <absolute path>` | Click the zip name after export | Reveal the zip in the file manager |
| `/trimp <overwrite\|skip\|overwriteall\|cancelall> [rel]` | Conflict buttons | Resolve a pending import |

### Help

```
/help recmode   /help rcm
/help delrec
/help export    /help rcex
/help import    /help rcim
```

> This `/help` is **registered client-side**. If the server or another mod also provides `/help`, the client version may take priority or behave oddly; in that case use the bare commands (`/recmode`, `/delrec`, `/rcex`, `/rcim`) to print help.

---

## Languages

All chat messages, help screens and button labels come from the mod's language tables; key binding names come from the vanilla language files. **Regional variants are grouped onto a single translation**, so you do not maintain one file per region.

| Group | Client language codes | File |
| --- | --- | --- |
| English | `en_*` (en_us, en_gb, en_au, en_ca, en_nz, …) | `en_us.json` |
| Simplified Chinese | `zh_cn`, `zh_sg`, `zh_hans_*` and any other `zh_*` | `zh_cn.json` |
| Traditional Chinese | `zh_tw`, `zh_hk` (Hong Kong), `zh_mo` (Macau), `zh_hant_*` | `zh_tw.json` |
| Anything else | every other language code | falls back to `en_us.json` |

- The whole grouping rule lives in `Lang.group(String)` — change grouping in that one place.
- Language files are under `src/main/resources/assets/terrainrecorder/lang/`, in Minecraft's own format (`"key": "text"`) with `%s` / `%d` placeholders.
- To add a language: copy `en_us.json`, translate it, name it after the language code (e.g. `ja_jp.json`), then make `Lang.group` map that code to the file name (e.g. `group("ja_jp")` returns `ja_jp`).
- Missing entries fall back to English per key, never to the raw key name.

Currently shipped:

| Language | Key names | Chat / help |
| --- | --- | --- |
| English (US/UK/…) | ✅ | ✅ |
| Simplified Chinese | ✅ | ✅ |
| Traditional Chinese (Taiwan / Hong Kong / Macau) | ✅ | ✅ |

---

## How it works

1. **Source of data** — chunks the client has received and still keeps in memory (`ClientLevel`). Every `LevelChunkSection` is walked, air is skipped, and only the block id from `BuiltInRegistries.BLOCK` is stored (e.g. `minecraft:stone`).
2. **No networking** — no custom payloads are registered and the server is never modified; client commands are dispatched locally.
3. **Scan range** — **±10 chunks (~160 blocks)** horizontally around the player. Anything farther is neither scanned nor drawn.
4. **Updates (no periodic full rescans)**:
   - **Block changes** are applied incrementally in real time. On 1.21.11 the client applies authoritative server changes through
     `ClientLevel.setServerVerifiedBlockState(...)`, so that is the injected entry point (plus the local `ClientLevel.setBlock(...)` path).
     Other players' placements/breaks show up in `/find` and `/recmode` immediately.
   - **Chunk data**: `CHUNK_LOAD` (including server chunk resends) queues the chunk for a scan.
   - **Initial scan**: on start, chunks in range are queued **nearest-first** and processed with a **2.5 ms per tick** budget.
     The action bar shows the remaining chunk count; once the queue drains there is **no periodic scanning at all**.
   - Chunks that were loaded earlier outside the range are queued when they come into range. Thanks to "scan once + incremental updates",
     idle cost is essentially zero.
5. **Alignment** — a **31 × 5 × 31** region around the player (x/z ±15, y −2..+2) is compared position by position against a snapshot (allowing one whole-snapshot translation). At least **80%** identical block ids are required to consider it "the same terrain". Snapshots are checked newest-first; the first one reaching **95%** is used immediately, otherwise the one with the most matching blocks wins.
   - The region is read straight from the world, and alignment votes use a sparse sample (radius ±4 chunks, stride 4 → about 1/64 of the reads), so `/find` no longer stalls on startup.
   - The overlap required to accept a translation adapts to the snapshot / sample size (roughly 8–100 blocks), so one or two coincidental blocks cannot lock a bogus offset.
6. **Granularity** — only block ids are compared. Same id with a different state (facing, waterlogged, slab half, …) counts as equal; a different id counts as a difference.
   - Only **chunks the snapshot covers** (chunks loaded when it was captured, shifted by the alignment offset) are compared; uncovered areas count as "unknown".
   - More than **5000** differences in one `/find` stops highlighting and warns: it usually means the snapshot does not match this place (captured elsewhere, or its range is smaller than your view distance).
   - At most **800** boxes are drawn per frame; the selection depends only on positions, not on the view direction (off-screen boxes are clipped by the GPU), so rotating the camera never reshuffles them.
7. **Leaving detection** — every 2 seconds during `/find`, the player's chunk and its surrounding ring are checked against the snapshot's coverage; only three consecutive misses stop `/find`.

### Performance notes

- Enabling `/find` performs a **single** nearest-first scan (at most 2.5 ms per tick). Once it finishes, idle cost is essentially zero;
  later block changes add/remove boxes through the mixin instead of periodic full rescans.
- Tuning constants if you want more speed or less work:

| Constant | Where | Meaning |
| --- | --- | --- |
| `SCAN_BUDGET_NANOS` | `TerrainManager` | scan time budget per tick (default 2.5 ms) |
| `MAX_HIGHLIGHT` | `TerrainManager` | stop highlighting above this many differences (default 5000) |
| `MAX_BOXES` | `FindRenderer` | max boxes drawn per frame (default 800) |
| `RAMP_STEP` | `FindRenderer` | new boxes added per frame while ramping (default 100) |

---

## Where data is stored

| Content | Path |
| --- | --- |
| Singleplayer | `<.minecraft>/saves/<world>/terrain_snapshots/singleplayer/` |
| Multiplayer | `<.minecraft>/terrain_snapshots/<server address>/` |
| Exported / downloaded zips | `<terrain_snapshots>/zips/` |

- The folder name comes from the server address (`:` and `/` become `_`), so **editing the saved server address switches to a different folder**.
- Default file name is `snapshot_yyyyMMdd_HHmmss.snap`; **you may rename it to anything** and use that name in commands (e.g. `/find castle`, `/delrec castle`). Ids resolve as `snapshot_<name>.snap` first, then `<name>.snap`; list priority is "Chinese name → English custom name → default `snapshot_*`".
- Snapshots are **NDJSON** (one block per line), plain text and easy to script:

```json
{"x":100,"y":64,"z":-20,"b":"minecraft:stone"}
{"x":101,"y":64,"z":-20,"b":"minecraft:dirt"}
```

> A snapshot records **every non-air block** in range (stone, deepslate, …). At a render distance of 12 chunks a single `/rec` can write tens of millions of lines and hundreds of MB, and comparison loads the whole file into memory. Recommendations:
> - lower the render distance, or capture in several smaller areas;
> - give the client a larger heap (4 GB or more);
> - `/rec export` a backup before running `recmode`.
>
> Reads and writes are streaming (one line at a time, `.tmp` + atomic replace). A corrupted line is skipped and logged instead of crashing the command.
> Files written by this mod use a **fast parser** (about 3–4× faster than Gson), and the same pass records which chunks have data, so `/find`
> can expand its coverage index without walking millions of blocks again.

---

## Import / export / download

- **Export** always uses `<server>/<file>.snap` inside the zip, so:
  - a zip exported on server A is restored into A's folder when imported while on server B (B's files are never overwritten), and shows up in `/find` completion;
  - you can copy the whole `zips/` folder to someone else and let them run `/rcim -x <file>`.
- **Import** only accepts `.snap`; conflicts can be resolved one by one, `-f` overwrites everything.
- **Mobile / headless environments**: file dialogs may be unavailable — export with `/rcex -o <name>` and import with `/rcim -x <zip>` (zip under `zips/`).
- **Download** supports `http://` / `https://` only; the built-in `jx-bjd-dmm` shortcut points at the sample pack published by the author. It runs in the background and hands over to the import flow when done.

---

## Limitations

1. **Only loaded chunks are visible** — unloaded areas are neither recorded nor compared. Walking further during `recmode` adds the new area, but old data stays old.
2. **Block ids only** — no block states (facing, waterlogged, growth stage, …), no block entities (chest contents, sign text, spawner data), no entities, light or biomes.
3. **No dimension recorded** — Overworld / Nether / End share one server folder and file names carry no dimension; reusing the same snapshots across dimensions may align to the wrong terrain.
4. **Hard 10-chunk limit** for scanning and drawing; `/recmode` does not scan farther either.
5. **`/find` shows only "extra" blocks**, never "the snapshot had this but the world no longer does"; use `/recmode min` for that.
6. **`/recmode stop` overwrites the base snapshot** with no automatic backup.
7. **Coarse comparison** — same id with a different state is not a difference, so rotating stairs will not be flagged.
8. **Client commands can collide with server commands** — if a server plugin also provides `/find` or `/rec`, the client command takes part in parsing and may intercept a command meant for the server; `/help` has the same risk.
9. **File dialogs need a desktop environment** (Windows / macOS / Linux); otherwise use `-x` / `-o`.

---

## Building from source

Requires **JDK 21** (Gradle uses `JAVA_HOME`); the first build downloads Minecraft / Fabric dependencies.

```bash
# Windows
gradlew.bat build

# macOS / Linux
./gradlew build
```

Output: `build/libs/terrainrecorder-1.0.0.jar` (plus a `-sources.jar`).

Run the client in a dev environment with:

```bash
gradlew.bat runClient
```

Versions live in `gradle.properties`:

```properties
minecraft_version=1.21.11
loader_version=0.19.5
fabric_api_version=0.141.6+1.21.11
version=1.0.0
```

---

## Project layout

```
├─ src/main/java/com/terrainrecorder/TerrainRecorderMod.java   constants / logger / id helper
├─ src/main/resources/
│  ├─ fabric.mod.json                mod metadata (client-only)
│  ├─ terrainrecorder.mixins.json    mixin config
│  └─ assets/terrainrecorder/
│     ├─ icon.png
│     └─ lang/{en_us,zh_cn,zh_tw}.json   translations
├─ src/client/java/com/terrainrecorder/
│  ├─ TerrainManager.java            core: state machine, scan scheduler, alignment, commands, zip I/O
│  ├─ Lang.java                      language tables and regional grouping
│  ├─ snapshot/Snapshot.java         snapshot model: chunk walk, streaming NDJSON read/write
│  ├─ client/TerrainRecorderClient.java  entry point: command tree, hotkeys, events
│  ├─ client/FindRenderer.java       world rendering: see-through red / blue boxes
│  └─ mixin/ClientBlockChangeMixin.java  ClientLevel.setBlock hook (live block changes)
├─ build.gradle / settings.gradle / gradle.properties
├─ README.md (Chinese) / README.en.md (this file) / LICENSE
```

---

## License

**CC0-1.0**, by **JimmyXiao**.
