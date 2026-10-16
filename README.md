# Wallpaper Manager — Kotlin / Compose Desktop

A Kotlin and [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) rewrite **inspired by** [adipro1/wallpaper-manager](https://github.com/adipro1/wallpaper-manager), a Windows wallpaper application written in Python/CustomTkinter. This is a **work-in-progress port**, not yet a feature-complete replacement.

The original app is Windows-only; this port uses a cross-platform JVM UI and isolates platform-specific desktop calls. Both projects are independent. This repository is licensed under GPL-3.0.

## Implemented

- Compose Material 3 desktop interface with Home, Desktop, Lock Screen, Library, Playlists, Schedule, Settings navigation
- Import single files or recursively import folders without moving original media
- JPG, PNG, BMP, GIF and WebP image metadata and thumbnails, plus MP4/WEBM catalog entries
- Search, image/video filters, favorites, current wallpaper and recent imports
- Static desktop wallpaper application: Windows (Win32 and registry positioning), macOS (AppleScript), Linux GNOME/KDE (desktop commands)
- Windows original wallpaper backup and restore, WEBP/GIF first-frame conversion to PNG
- Random/next image selection and Fill/Fit/Stretch/Center/Tile on Windows
- SQLite library and settings, playlist memberships and daily schedule rules
- Sequential and shuffle-bag playlists; configurable interval, start/stop/next and persisted playback progress
- Schedule catch-up across restarts and enable/disable individual daily rules
- AWT system tray with Open, Next, Random, Stop playlist, Exit; close-to-tray setting and optional --minimized launch
- Dark/light mode and automatic CI compilation/testing on Windows, macOS and Linux
- Removing media entries **never deletes user files**

## Not implemented yet

The Python original also implements important Windows-specific capabilities that need dedicated native integration:

- Live MP4/WEBM video wallpaper behind Explorer icons (WorkerW + libmpv)
- WinRT lock-screen wallpaper, picking frames from video, Windows Spotlight restore
- Start-at-login, single instance, fullscreen/battery/lock-screen auto-pause
- Drag and drop, multi-select bulk actions, update checking and installer branding
- Video thumbnail generation, built-in video preview and detailed video duration metadata

**Video files are imported into the library but cannot yet be set as live wallpapers.** Playlist and schedule playback currently support static images only. Those limitations are visible in the UI and not silently emulated with a foreground player.

## Development with Devbox

The JVM toolchain is managed with [Devbox](https://www.jetify.com/devbox/): **JDK 21**, Gradle and actionlint are declared in `devbox.json`. Install Devbox once, then on Linux/macOS:

```bash
devbox run run              # Launch the Compose Desktop app
devbox run test             # Unit tests
devbox run lint-workflows   # Validate the GitHub Actions YAML files
devbox run package-deb      # Linux only
devbox run package-dmg      # macOS only
```

The project can also be opened directly in IntelliJ IDEA. The latest stable Devbox CLI is selected by `jetify-com/devbox-install-action@v0.15.0` in CI. Gradle/JDK packages are resolved through Devbox.

**Windows exception:** Devbox/Nix do not currently provide native Windows tooling. For native Windows development, use JDK 21 and Gradle with `gradle run`, `gradle test` and `gradle packageMsi`. CI uses the JDK 21, Gradle and WiX already installed on the `windows-2025` hosted runner rather than downloading them through another installer.

## Releases

The [Release workflow](.github/workflows/release.yml) publishes installers for **Windows x64 (.msi)**, **macOS ARM64 (.dmg)** and **Linux x64 (.deb)**.

To release after merging the port to `main`:

1. Open **Actions → Release → Run workflow**.
2. Select `main`, enter a new version such as `0.1.0`, and optionally check **prerelease**.
3. After *all three* platform tests and installers succeed, the workflow creates GitHub release `v0.1.0` and uploads the three installers plus a `SHA256SUMS.txt` verification file.

Alternatively, pushing an existing tag such as `v0.1.0` starts the same release workflow. Only stable `vMAJOR.MINOR.PATCH` tags are accepted. A manual release refuses to reuse an existing tag; an existing published GitHub release is never overwritten.

The version is passed to Gradle through `RELEASE_VERSION` (local development defaults to `0.1.0`). Windows and Linux native versions match the tag. **macOS exception:** jpackage rejects a zero major version, so the internal macOS bundle version maps `0.1.0` to `1.1.0` (major + 1, preserving upgrade order); the GitHub release tag and downloadable DMG filename still use `v0.1.0`. Building a release requires the corresponding native operating system; native macOS and Windows installers cannot be cross-compiled from Linux.

The installers are currently **unsigned**. Windows SmartScreen and macOS Gatekeeper may show warnings. Do not advertise the installers as feature-complete yet: video/lock-screen capabilities remain unported.

## Data and safety

The Kotlin port intentionally **does not read or modify the Python application's database**.

- Windows: %LOCALAPPDATA%/WallpaperManagerKotlin
- macOS: ~/Library/Application Support/WallpaperManagerKotlin
- Linux: $XDG_DATA_HOME/wallpaper-manager-kotlin (or ~/.local/share/wallpaper-manager-kotlin)
- Override in tests using the WPM_KOTLIN_DATA_DIR environment variable.

The app stores absolute media paths; moving a file outside the app can make its entry unavailable. Original Windows wallpaper backup is copied into the Kotlin app's data directory on first application. macOS/Linux restore-original is not yet supported.

Wallpaper changes call operating-system services and may behave differently depending on the desktop environment. The JVM UI runs on all three platforms, but not every desktop environment is supported.

On Linux, the active session selects the wallpaper backend: GNOME uses `gsettings`, and KDE Plasma uses `plasma-apply-wallpaperimage` (which must be installed). Detection reads `XDG_CURRENT_DESKTOP`, falling back to `XDG_SESSION_DESKTOP` and `DESKTOP_SESSION` when earlier values are absent or blank. An unknown or missing session reports an error. A successful settings write for an inactive desktop is never used to infer support.

## Architecture

```text
src/main/kotlin/wallpaper/
  Main.kt             Compose shell, navigation and media library UI
  Pages.kt            Playlist, schedule and settings pages
  Model.kt            Media, playlist and schedule domain models
  Store.kt            SQLite storage, relational references and settings
  Controller.kt       UI state, serialized actions and automation loop
  DailySchedule.kt    Date selection and non-repeating shuffle bag
  DesktopWallpaper.kt Operating-system wallpaper adapters
  LinuxWallpaper.kt   Session detection and GNOME/KDE command routing
  MediaFiles.kt       File import, image metadata and thumbnails
  TraySupport.kt      AWT system tray and close-to-tray controls
src/test/kotlin/wallpaper/
  DailyScheduleTest.kt
  PlaylistOrderTest.kt
  StoreTest.kt
```


Please retain attribution to [the original author and project](https://github.com/adipro1/wallpaper-manager) when sharing this alternative. The remaining native integrations should be approached as separate, testable changes rather than hiding OS-specific logic in composables.
