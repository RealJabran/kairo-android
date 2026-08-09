# Kairo

Kairo is a polished personal Android anime browser, watchlist, and offline library. It uses the independent package `app.kairo.anime`, a custom visual identity, responsive Jetpack Compose screens, WorkManager downloads, and a Media3/ExoPlayer video player.

## Highlights

- Rich adaptive Discover, Detail, Library, and Settings screens
- Correct status-bar and navigation-bar safe areas
- Episode-specific audio-language selection, including Hindi Dubbed whenever the active source provides it
- Video-quality selection with an estimated size before download
- Live download speed, transferred size, and completed file size
- Existing-download detection with watch and alternate-quality actions
- Continue-watching progress and a personal watchlist
- User-selected download folder through Android's Storage Access Framework
- Encrypted and unencrypted HLS segment support
- Fully custom Kairo Media3 player with a cinematic controller, precise timeline scrubbing, buffering state, replay, transport controls, and automatic control hiding
- Playback-aware screen wake lock so the display remains on while a video is playing or buffering
- Download-first playback by default, with an optional Instant playback setting that shows both **Watch now** and **Download**
- Reliable full-screen left/right double-tap zones for 10-second rewind and forward; a single tap reveals controls
- Persistent MX Player-style vertical gesture zones: brightness on the left and media volume on the right, whether controls are visible or hidden
- Always-visible previous/next episode buttons that rebuild the full source queue when playback starts from a single downloaded file
- Playback speeds from 0.5× to 2×, embedded audio/subtitle track selectors, control lock, picture-in-picture, and Fit/Crop/Stretch/100% display cycling
- One-tap display-mode cycling through Fit, Crop, Stretch, and 100%
- Credential-free public OpenSubtitles search inside the player, with format-aware download and forced text-track selection
- In-player quality switching between Auto and every resolved stream quality without losing playback position
- A provider-adapter system for streaming catalogs, Stremio-compatible add-ons, AniList discovery, Jellyfin, and on-device folders
- A native Stremio add-on manager with manifest validation, automatic capability detection, enable/remove controls, and drag-free priority arrows
- Cross-add-on resolution: browse with a catalog add-on, then check every enabled stream add-on in priority order
- Private display of personalized add-on URLs and explicit filtering of unsupported torrent-only results

## Sources

| Source | Browse/search | Play | Download | Notes |
| --- | --- | --- | --- | --- |
| Kairo Stream | Yes | Yes | Yes | Built-in compatible streaming catalog |
| Stremio add-on | When provided | Direct HTTP(S) streams | Direct HTTP(S) streams | Catalog, metadata, and resolver add-ons can work together |
| AniList | Yes | No | No | High-quality discovery and metadata; use **Find** to search a playable provider |
| Jellyfin | Yes | Yes | Yes | Connects to a personal Jellyfin server and preserves embedded audio/subtitle tracks |
| On device | Yes | Yes | Already local | Reads anime from a folder selected with Android's system folder picker |

To connect Jellyfin, open **Settings → Add a source → Jellyfin**, enter the server URL and an access token/API key, then validate the connection. Prefer HTTPS whenever the server is reachable outside your home network. Kairo keeps the token in private app storage and disables Android backup for the app.

Kairo defaults to downloading remote episodes before playback. To stream without downloading, enable **Settings → Instant playback**. Episode sheets will then offer both **Watch now** and **Download**. Local files always remain directly playable.

### Stremio-compatible add-ons

Open **Settings → Add a source → Stremio**, then paste either the add-on's full `https://…/manifest.json` URL or its `stremio://…` install link. Kairo also registers as a handler for Stremio install links, so tapping **Install** on an add-on configuration page can open the same prefilled validation dialog directly. Kairo validates the manifest and reads the add-on name, version, and advertised catalog, metadata, stream, and subtitle capabilities automatically. If an add-on has its own configuration page, configure it first and use the personalized install URL it gives you.

Installed add-ons can be enabled, disabled, removed, or moved up and down in Settings. Order is significant: Kairo asks enabled stream add-ons in that order and uses the order to rank otherwise equivalent results. A catalog-only add-on can still power Discover while separate stream add-ons resolve episodes for the same IDs.

Kairo currently plays direct HTTP(S) media URLs, including HLS and direct video files. It intentionally skips bare torrent hashes, NZB/archive transports, YouTube IDs, and external web pages because those require separate playback engines. A configured add-on or debrid service may work when it returns an authorized direct HTTP(S) stream. Only install add-ons and access media you trust and are permitted to use. Startup speed ultimately depends on the add-on, its upstream host/CDN, your account tier, and your network; Kairo caches manifests and short-lived stream results but cannot make a slow upstream perform like Netflix.

For captions without a local file, use the caption button inside the player, choose a language, and select a result. Kairo resolves the title through Stremio's official Cinemeta catalog and searches its public OpenSubtitles v3 add-on—no API key, username, or password is required.

Jellyfin addresses are specific to your own server. Typical forms are `http://192.168.1.50:8096` on home Wi-Fi, `http://100.64.0.10:8096` over a private VPN such as Tailscale, or `https://jellyfin.yourdomain.com` behind a secure reverse proxy. Create a dedicated key under **Jellyfin Dashboard → API Keys**, then enter that key with the reachable server URL.

The built-in compatible source is `https://anidb.app`, so it does not need to be added again. Custom compatible deployments commonly use addresses such as `http://192.168.1.50:3000` on a LAN or `https://anime.yourdomain.com` when hosted. Those are address patterns—not public streaming services—and only work when a server implementing the contract below is actually deployed there.

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
output/Kairo-2.1.1-test.apk
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
