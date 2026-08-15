package app.kairo.anime.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class KairoPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("kairo_preferences", Context.MODE_PRIVATE)

    init {
        if (listOf("opensubtitles_api_key", "opensubtitles_token", "opensubtitles_base_url", "subtitle_language", "auto_download_captions").any(prefs::contains)) {
            prefs.edit()
                .remove("opensubtitles_api_key").remove("opensubtitles_token").remove("opensubtitles_base_url")
                .remove("subtitle_language").remove("auto_download_captions")
                .apply()
        }
    }

    var downloadTreeUri: String?
        get() = prefs.getString("download_tree", null)
        set(value) { prefs.edit().putString("download_tree", value).apply() }

    var defaultLanguage: String
        get() = prefs.getString("default_language", "Japanese") ?: "Japanese"
        set(value) { prefs.edit().putString("default_language", value).apply() }

    var selectedSourceId: String
        get() = prefs.getString("selected_source", "anidb") ?: "anidb"
        set(value) { prefs.edit().putString("selected_source", value).apply() }

    var localMediaTreeUri: String?
        get() = prefs.getString("local_media_tree", null)
        set(value) { prefs.edit().putString("local_media_tree", value).apply() }

    var instantPlaybackEnabled: Boolean
        get() = prefs.getBoolean("instant_playback", false)
        set(value) { prefs.edit().putBoolean("instant_playback", value).apply() }

    fun sources(): List<SourceDefinition> {
        val builtIns = listOf(
            SourceDefinition("anidb", "Kairo Stream", "https://anidb.app", true, true, SourceKind.KAIRO_COMPATIBLE),
            SourceDefinition("anilist", "AniList", "https://graphql.anilist.co", true, true, SourceKind.ANILIST),
            SourceDefinition("local", "On device", "content://local", true, true, SourceKind.LOCAL)
        )
        val raw = prefs.getString("sources", "[]") ?: "[]"
        return buildList {
            addAll(builtIns)
            runCatching {
                val array = JSONArray(raw)
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    add(SourceDefinition(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        baseUrl = item.getString("baseUrl").trimEnd('/'),
                        enabled = item.optBoolean("enabled", true),
                        kind = runCatching { SourceKind.valueOf(item.optString("kind", SourceKind.KAIRO_COMPATIBLE.name)) }.getOrDefault(SourceKind.KAIRO_COMPATIBLE),
                        authToken = item.optString("authToken"),
                        userId = item.optString("userId"),
                        addonId = item.optString("addonId"),
                        addonVersion = item.optString("addonVersion"),
                        addonDescription = item.optString("addonDescription"),
                        addonResources = item.optString("addonResources"),
                        addonP2p = item.optBoolean("addonP2p")
                    ))
                }
            }
        }
    }

    fun addSource(name: String, baseUrl: String): SourceDefinition {
        val source = SourceDefinition(UUID.randomUUID().toString(), name.trim(), baseUrl.trim().trimEnd('/'))
        saveSources(sources().filterNot { it.builtIn } + source)
        return source
    }

    fun addJellyfinSource(name: String, baseUrl: String, authToken: String, userId: String): SourceDefinition {
        val source = SourceDefinition(
            id = UUID.randomUUID().toString(), name = name.trim(), baseUrl = baseUrl.trim().trimEnd('/'),
            kind = SourceKind.JELLYFIN, authToken = authToken.trim(), userId = userId
        )
        saveSources(sources().filterNot { it.builtIn } + source)
        return source
    }

    fun addStremioSource(
        name: String,
        manifestUrl: String,
        addonId: String,
        addonVersion: String,
        description: String,
        resources: Set<String>,
        p2p: Boolean
    ): SourceDefinition {
        val current = sources().filterNot { it.builtIn }
        val duplicate = current.firstOrNull {
            it.kind == SourceKind.STREMIO &&
                (it.baseUrl.equals(manifestUrl, true) || addonId.isNotBlank() && it.addonId == addonId)
        }
        val source = SourceDefinition(
            id = duplicate?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            baseUrl = manifestUrl.trim(),
            enabled = true,
            kind = SourceKind.STREMIO,
            addonId = addonId,
            addonVersion = addonVersion,
            addonDescription = description,
            addonResources = resources.sorted().joinToString(","),
            addonP2p = p2p
        )
        saveSources(current.filterNot { it.id == duplicate?.id } + source)
        return source
    }

    fun removeSource(id: String) {
        saveSources(sources().filterNot { it.builtIn || it.id == id })
        if (selectedSourceId == id) selectedSourceId = "anidb"
    }

    fun toggleSource(id: String) {
        saveSources(sources().filterNot { it.builtIn }.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun moveSource(id: String, direction: Int) {
        if (direction == 0) return
        val custom = sources().filterNot { it.builtIn }.toMutableList()
        val from = custom.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + direction).coerceIn(0, custom.lastIndex)
        if (from == to) return
        val moved = custom.removeAt(from)
        custom.add(to, moved)
        saveSources(custom)
    }

    private fun saveSources(items: List<SourceDefinition>) {
        val array = JSONArray()
        items.forEach { source ->
            array.put(JSONObject().apply {
                put("id", source.id)
                put("name", source.name)
                put("baseUrl", source.baseUrl)
                put("enabled", source.enabled)
                put("kind", source.kind.name)
                put("authToken", source.authToken)
                put("userId", source.userId)
                put("addonId", source.addonId)
                put("addonVersion", source.addonVersion)
                put("addonDescription", source.addonDescription)
                put("addonResources", source.addonResources)
                put("addonP2p", source.addonP2p)
            })
        }
        prefs.edit().putString("sources", array.toString()).apply()
    }

    @Synchronized fun downloads(): List<DownloadRecord> {
        val raw = prefs.getString("downloads", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                DownloadRecord(
                    id = item.getString("id"), animeTitle = item.getString("animeTitle"),
                    episodeLabel = item.getString("episodeLabel"), language = item.optString("language"),
                    quality = item.optString("quality"), uri = item.getString("uri"),
                    bytes = item.optLong("bytes"), completedAt = item.optLong("completedAt"),
                    animeId = item.optString("animeId"), animeImageUrl = item.optString("animeImageUrl"),
                    animeUrl = item.optString("animeUrl"), sourceId = item.optString("sourceId", "anidb"),
                    episodeNumber = item.optInt("episodeNumber"), episodeId = item.optString("episodeId"),
                    seasonNumber = item.optInt("seasonNumber"), subtitleUri = item.optString("subtitleUri")
                )
            }.sortedByDescending { it.completedAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun addDownload(record: DownloadRecord): Boolean {
        val items = downloads().filterNot {
            it.id == record.id || (
                it.animeTitle.equals(record.animeTitle, true) &&
                    it.episodeLabel.equals(record.episodeLabel, true) &&
                    it.language.equals(record.language, true) &&
                    it.quality.equals(record.quality, true)
                )
        } + record
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("animeTitle", item.animeTitle); put("episodeLabel", item.episodeLabel)
                put("language", item.language); put("quality", item.quality); put("uri", item.uri)
                put("bytes", item.bytes); put("completedAt", item.completedAt)
                put("animeId", item.animeId); put("animeImageUrl", item.animeImageUrl); put("animeUrl", item.animeUrl)
                put("sourceId", item.sourceId); put("episodeNumber", item.episodeNumber)
                put("episodeId", item.episodeId); put("seasonNumber", item.seasonNumber)
                put("subtitleUri", item.subtitleUri)
            })
        }
        return prefs.edit().putString("downloads", array.toString()).commit()
    }

    @Synchronized fun removeDownload(id: String) {
        val retained = downloads().filterNot { it.id == id }
        val array = JSONArray()
        retained.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("animeTitle", item.animeTitle); put("episodeLabel", item.episodeLabel)
                put("language", item.language); put("quality", item.quality); put("uri", item.uri)
                put("bytes", item.bytes); put("completedAt", item.completedAt)
                put("animeId", item.animeId); put("animeImageUrl", item.animeImageUrl); put("animeUrl", item.animeUrl)
                put("sourceId", item.sourceId); put("episodeNumber", item.episodeNumber)
                put("episodeId", item.episodeId); put("seasonNumber", item.seasonNumber)
                put("subtitleUri", item.subtitleUri)
            })
        }
        prefs.edit().putString("downloads", array.toString()).apply()
        removePlaybackProgress(id)
    }

    @Synchronized fun playbackProgress(): List<PlaybackProgress> {
        val raw = prefs.getString("playback_progress", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                PlaybackProgress(
                    recordId = item.getString("recordId"), positionMs = item.optLong("positionMs"),
                    durationMs = item.optLong("durationMs"), updatedAt = item.optLong("updatedAt")
                )
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun savePlaybackProgress(progress: PlaybackProgress) {
        if (progress.recordId.isBlank() || progress.durationMs <= 0) return
        val items = playbackProgress().filterNot { it.recordId == progress.recordId } + progress
        savePlaybackProgressItems(items.sortedByDescending { it.updatedAt }.take(100))
    }

    @Synchronized fun removePlaybackProgress(recordId: String) {
        savePlaybackProgressItems(playbackProgress().filterNot { it.recordId == recordId })
    }

    private fun savePlaybackProgressItems(items: List<PlaybackProgress>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("recordId", item.recordId); put("positionMs", item.positionMs)
                put("durationMs", item.durationMs); put("updatedAt", item.updatedAt)
            })
        }
        prefs.edit().putString("playback_progress", array.toString()).apply()
    }

    @Synchronized fun watchlist(): List<WatchlistEntry> {
        val raw = prefs.getString("watchlist", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                WatchlistEntry(
                    anime = Anime(
                        id = item.getString("id"), title = item.getString("title"),
                        imageUrl = item.optString("imageUrl"), url = item.optString("url"),
                        score = item.optString("score"), type = item.optString("type", "TV"),
                        sourceId = item.optString("sourceId", "anidb")
                    ),
                    addedAt = item.optLong("addedAt")
                )
            }.sortedByDescending { it.addedAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun toggleWatchlist(anime: Anime): Boolean {
        val current = watchlist()
        val exists = current.any { it.anime.id == anime.id && it.anime.sourceId == anime.sourceId }
        val updated = if (exists) current.filterNot { it.anime.id == anime.id && it.anime.sourceId == anime.sourceId }
        else listOf(WatchlistEntry(anime, System.currentTimeMillis())) + current
        val array = JSONArray()
        updated.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.anime.id); put("title", entry.anime.title); put("imageUrl", entry.anime.imageUrl)
                put("url", entry.anime.url); put("score", entry.anime.score); put("type", entry.anime.type)
                put("sourceId", entry.anime.sourceId); put("addedAt", entry.addedAt)
            })
        }
        prefs.edit().putString("watchlist", array.toString()).apply()
        return !exists
    }
}
