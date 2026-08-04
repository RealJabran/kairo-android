package app.kairo.anime.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class KairoPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("kairo_preferences", Context.MODE_PRIVATE)

    var downloadTreeUri: String?
        get() = prefs.getString("download_tree", null)
        set(value) { prefs.edit().putString("download_tree", value).apply() }

    var defaultLanguage: String
        get() = prefs.getString("default_language", "Japanese") ?: "Japanese"
        set(value) { prefs.edit().putString("default_language", value).apply() }

    var selectedSourceId: String
        get() = prefs.getString("selected_source", "anidb") ?: "anidb"
        set(value) { prefs.edit().putString("selected_source", value).apply() }

    fun sources(): List<SourceDefinition> {
        val builtIn = SourceDefinition("anidb", "AniDB", "https://anidb.app", true, true)
        val raw = prefs.getString("sources", "[]") ?: "[]"
        return buildList {
            add(builtIn)
            runCatching {
                val array = JSONArray(raw)
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    add(SourceDefinition(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        baseUrl = item.getString("baseUrl").trimEnd('/'),
                        enabled = item.optBoolean("enabled", true)
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

    fun removeSource(id: String) {
        saveSources(sources().filterNot { it.builtIn || it.id == id })
        if (selectedSourceId == id) selectedSourceId = "anidb"
    }

    fun toggleSource(id: String) {
        saveSources(sources().filterNot { it.builtIn }.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    private fun saveSources(items: List<SourceDefinition>) {
        val array = JSONArray()
        items.forEach { source ->
            array.put(JSONObject().apply {
                put("id", source.id)
                put("name", source.name)
                put("baseUrl", source.baseUrl)
                put("enabled", source.enabled)
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
                    episodeNumber = item.optInt("episodeNumber")
                )
            }.sortedByDescending { it.completedAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun addDownload(record: DownloadRecord) {
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
            })
        }
        prefs.edit().putString("downloads", array.toString()).commit()
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
