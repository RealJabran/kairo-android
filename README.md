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
- Playback-aware screen wake lock so the display remains on while a video is playing or buffering
- Download-first playback by default, with an optional Instant playback setting that shows both **Watch now** and **Download**
- Full-screen left/right tap zones for quick 10-second rewind and forward
- One-tap display-mode cycling through Fit, Crop, Stretch, and 100%
- OpenSubtitles search and direct caption attachment from the player, plus optional sidecar captions for downloads
- A provider-adapter system for streaming catalogs, AniList discovery, Jellyfin, and on-device folders

## Sources

| Source | Browse/search | Play | Download | Notes |
| --- | --- | --- | --- | --- |
| Kairo Stream | Yes | Yes | Yes | Built-in compatible streaming catalog |
| AniList | Yes | No | No | High-quality discovery and metadata; use **Find** to search a playable provider |
| Jellyfin | Yes | Yes | Yes | Connects to a personal Jellyfin server and preserves embedded audio/subtitle tracks |
| On device | Yes | Yes | Already local | Reads anime from a folder selected with Android's system folder picker |

To connect Jellyfin, open **Settings → Add a source → Jellyfin**, enter the server URL and an access token/API key, then validate the connection. Prefer HTTPS whenever the server is reachable outside your home network. Kairo keeps the token in private app storage and disables Android backup for the app.

Kairo defaults to downloading remote episodes before playback. To stream without downloading, enable **Settings → Instant playback**. Episode sheets will then offer both **Watch now** and **Download**. Local files always remain directly playable.

For captions without a local file, open **Settings → Online captions** and connect an OpenSubtitles.com account using its consumer API key. Kairo uses the password only for the login request and never saves it. The player can then search and attach a caption directly. **Save captions with downloads** optionally stores the best language match beside each newly downloaded episode. OpenSubtitles access tokens expire periodically; reconnect in Settings if the service reports an expired connection.

For an on-device library, choose **Settings → On-device anime folder**. A useful layout is:

```text
Anime/
  Series name/
    cover.jpg
    Season 01/
      S01E01.mkv
      S01E02.mkv
```

Loose video files and common names such as `Episode 01`, `E01`, and `S01E01` are also recognized.

## Build

On Linux, run:

```bash
chmod +x build-apk.sh
./build-apk.sh
```

The build script downloads a pinned Gradle distribution and lets the Android Gradle plugin provision the required SDK. The test APK is written to:

```text
output/Kairo-1.4.0-test.apk
```

You can also open the project directly in a recent Android Studio release and run the `app` configuration.

## Compatible source contract

A custom source must expose the same public routes used by the built-in provider:

- `/browse`
- `/search/suggestions?q=...`
- `/api/frontend/anime/{id}/episodes`
- `/api/frontend/episode/{id}/languages`

The language response must contain an `embed_url` that ultimately exposes an HLS (`.m3u8`) stream. Arbitrary websites cannot be added without writing a dedicated adapter for their HTML/API structure.

Each different service belongs in a dedicated `AnimeSourceAdapter`. This keeps catalog discovery separate from episode lookup, language selection, playable qualities, and delivery type. New adapters can therefore declare whether media is HLS, a direct file, or already local without leaking provider-specific behavior into the UI.
