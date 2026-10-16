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
- Dark/light mode and automatic CI compilation/testing on Windows, macOS and Linux
- Removing media entries **never deletes user files**

## Not implemented yet

The Python original also implements important Windows-specific capabilities that need dedicated native integration:

- Live MP4/WEBM video wallpaper behind Explorer icons (WorkerW + libmpv)
- WinRT lock-screen wallpaper, picking frames from video, Windows Spotlight restore
- System tray, start-at-login, single instance, fullscreen/battery/lock-screen auto-pause
- Drag and drop, multi-select bulk actions, update checking and installer assets
- Video thumbnail generation, built-in video preview and detailed video duration metadata

**Video files are imported into the library but cannot yet be set as live wallpapers.** Playlist and schedule playback currently support static images only. Those limitations are visible in the UI and not silently emulated with a foreground player.

## Prerequisites and running

- **JDK 21**
- **Gradle 9.3** (or open the project in IntelliJ IDEA with Gradle support)
- Windows 10/11, macOS, or Linux with a GNOME/KDE-compatible wallpaper command

From the project root:

```bash
gradle run
gradle test
```

The app does not require a separate Python installation. To package on the appropriate host OS:

```bash
gradle packageMsi    # on Windows
gradle packageDmg    # on macOS
gradle packageDeb    # on Linux
```


Installers are generated with the Compose Desktop/JPackage integration. Release builds should be tested on each host platform; a package task on one OS does not produce installers for the other OSes.

## Data and safety

The Kotlin port intentionally **does not read or modify the Python application's database**.

- Windows: %LOCALAPPDATA%/WallpaperManagerKotlin
- macOS: ~/Library/Application Support/WallpaperManagerKotlin
- Linux: $XDG_DATA_HOME/wallpaper-manager-kotlin (or ~/.local/share/wallpaper-manager-kotlin)
- Override in tests using the WPM_KOTLIN_DATA_DIR environment variable.

The app stores absolute media paths; moving a file outside the app can make its entry unavailable. Original Windows wallpaper backup is copied into the Kotlin app's data directory on first application. macOS/Linux restore-original is not yet supported.

Wallpaper changes call operating-system services and may behave differently depending on the desktop environment. The JVM UI runs on all three platforms, but not every desktop environment is supported.

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
  MediaFiles.kt       File import, image metadata and thumbnails
src/test/kotlin/wallpaper/
  DailyScheduleTest.kt
  PlaylistOrderTest.kt
  StoreTest.kt
```


Please retain attribution to [the original author and project](https://github.com/adipro1/wallpaper-manager) when sharing this alternative. The remaining native integrations should be approached as separate, testable changes rather than hiding OS-specific logic in composables.
