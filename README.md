<div align="center">

# Kairo

### Your anime. Your sources. One cinematic Android player.

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![CI](https://github.com/RealJabran/kairo-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/RealJabran/kairo-android/actions/workflows/android-ci.yml)
[![Latest release](https://img.shields.io/github/v/release/RealJabran/kairo-android?display_name=tag&sort=semver)](https://github.com/RealJabran/kairo-android/releases/latest)

[Download APK](https://github.com/RealJabran/kairo-android/releases/latest) · [Report a bug](https://github.com/RealJabran/kairo-android/issues/new?template=bug_report.yml) · [Build from source](#build-from-source)

</div>

Kairo is a personal Android anime browser, watchlist, offline library, and Media3 player. It combines built-in discovery with Jellyfin, on-device folders, Kairo-compatible providers, and Stremio-compatible catalog or direct-stream add-ons.

> Kairo does not host media. Add only services you trust and are permitted to use. Bare torrents, NZB/archive transports, YouTube IDs, and external playback pages are intentionally unsupported.

## Install

1. Open the [latest GitHub Release](https://github.com/RealJabran/kairo-android/releases/latest).
2. Download the file ending in `.apk` under **Assets**.
3. Open it on Android and allow installation from your browser or file manager when prompted.

Android 8.0 or newer is required. The package name is `app.kairo.anime`.

## What makes Kairo different

| Experience | Included |
| --- | --- |
| Discover | Adaptive catalog grid, search, rich details, genres, status, and watchlist |
| Watch | Instant playback or download-first mode, selectable language and quality |
| Player | Media3, quality switching, online captions, gestures, PiP, playback speed, screen modes, next/previous episode |
| Offline | Live speed and size, safe replacement downloads, progress, completed-file sizes |
| Sources | Kairo Stream, AniList, Jellyfin, local folders, compatible servers, Stremio add-ons |
| Privacy | Personalized source URLs stay private in the UI; Android backup is disabled |

## Player highlights

- Double-tap the left or right half to seek backward or forward 10 seconds.
- Swipe vertically on the left for brightness and on the right for volume.
- Cycle Fit, Crop, Stretch, and 100% without opening another menu.
- Switch stream quality without losing the current position.
- Select embedded audio/subtitle tracks or search public online captions.
- Keep the screen awake during playback and continue in picture-in-picture.
- Move between previous and next episodes from the player.

## Offline downloads

Kairo downloads direct files and HLS streams into a folder you choose with Android's Storage Access Framework. Version 2.2 uses Media3's HLS export pipeline so adaptive streams are written as progressive MP4 files with:

- byte-range playlists handled correctly;
- separate HLS audio/video renditions combined into one playable file;
- encrypted HLS input handled by the media pipeline;
- an existing download preserved until its replacement is fully complete.

## Content sources

### Stremio-compatible add-ons

Open **Settings → Add a source → Stremio**, then paste an `https://…/manifest.json` URL or a `stremio://…` install link. Kairo can also receive Stremio install links directly from an add-on configuration page.

Kairo reads the manifest name, version, and capabilities automatically. Catalog-only add-ons can power Discover while separate enabled stream add-ons resolve matching media IDs. Use the arrows in Settings to control resolver priority.

Only direct HTTP(S) media returned by an add-on can be played. Startup speed depends on the add-on, its upstream CDN, your service account, and your network.

### Jellyfin

Open **Settings → Add a source → Jellyfin** and enter your server URL plus a dedicated API key. Common URL patterns include:

- Home network: `http://192.168.1.50:8096`
- Private VPN: `http://100.64.0.10:8096`
- Reverse proxy: `https://jellyfin.example.com`

Prefer HTTPS outside your home network. Credentials are stored in the app's private preferences and excluded from Android backup.

### On-device library

Choose **Settings → On-device anime folder**. Kairo understands common filenames such as `Episode 01`, `E01`, and `S01E01`. A recommended structure is:

```text
Anime/
  Series name/
    cover.jpg
    Season 01/
      S01E01.mkv
      S01E02.mkv
```

### Kairo-compatible server contract

A compatible custom server must expose working responses for all required routes:

```text
/browse
/search/suggestions?q=...
/api/frontend/anime/{id}/episodes
/api/frontend/episode/{id}/languages
```

Kairo validates a representative browse result, episode response, and language/embed response before saving the server.

## Build from source

### One-command Linux build

```bash
git clone https://github.com/RealJabran/kairo-android.git
cd kairo-android
chmod +x build-apk.sh
./build-apk.sh
```

The script provisions its private Gradle/Android build tools when needed and writes:

```text
output/Kairo-2.2.0.apk
```

If `JAVA_HOME` already points to JDK 17, the script reuses it. You can also open the project in a recent Android Studio release and run the `app` configuration.

## Project map

```text
app/src/main/java/app/kairo/anime/
├── data/       Models, preferences, repository, captions, source adapters
├── download/   WorkManager download and Media3 HLS export pipeline
├── player/     Full-screen Media3 player and gesture controls
└── ui/         Jetpack Compose application screens and theme
```

## Contributing

Bug reports and focused improvements are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Please do not post source credentials, personalized add-on URLs, copyrighted media, or private server addresses in issues.

## Release history

See [CHANGELOG.md](CHANGELOG.md) for notable changes and [GitHub Releases](https://github.com/RealJabran/kairo-android/releases) for installable APKs.
