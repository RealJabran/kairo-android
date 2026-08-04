# Kairo

Kairo is a polished personal Android anime browser, watchlist, and offline library. It uses the independent package `app.kairo.anime`, a custom visual identity, responsive Jetpack Compose screens, WorkManager downloads, and a Media3/ExoPlayer video player.

## Highlights

- Rich adaptive Discover, Detail, Library, and Settings screens
- Correct status-bar and navigation-bar safe areas
- Episode-specific audio-language selection
- Video-quality selection with an estimated size before download
- Live download speed, transferred size, and completed file size
- Existing-download detection with watch and alternate-quality actions
- Continue-watching progress and a personal watchlist
- User-selected download folder through Android's Storage Access Framework
- Encrypted and unencrypted HLS segment support
- Media3 player with seeking, speed, Fit/Crop/Stretch/100% modes, external caption files, and picture-in-picture
- Configurable AniDB-compatible content sources

## Build

On Linux, run:

```bash
chmod +x build-apk.sh
./build-apk.sh
```

The build script downloads a pinned Gradle distribution and lets the Android Gradle plugin provision the required SDK. The test APK is written to:

```text
output/Kairo-1.1.0-test.apk
```

You can also open the project directly in a recent Android Studio release and run the `app` configuration.

## Custom source contract

A custom source must expose the same public routes used by the built-in provider:

- `/browse`
- `/search/suggestions?q=...`
- `/api/frontend/anime/{id}/episodes`
- `/api/frontend/episode/{id}/languages`

The language response must contain an `embed_url` that ultimately exposes an HLS (`.m3u8`) stream. Arbitrary websites cannot be added without writing a dedicated adapter for their HTML/API structure.
