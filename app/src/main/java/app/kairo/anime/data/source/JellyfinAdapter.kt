package app.kairo.anime.data.source

import app.kairo.anime.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class JellyfinConnection(val serverName: String, val userId: String)

class JellyfinAdapter : AnimeSourceAdapter {
    override val kind = SourceKind.JELLYFIN

    override suspend fun browse(source: SourceDefinition): List<Anime> = items(source, null)

    override suspend fun search(query: String, source: SourceDefinition): List<Anime> =
        if (query.isBlank()) browse(source) else items(source, query)

    override suspend fun details(anime: Anime, source: SourceDefinition): AnimeDetails {
        val item = item(source, anime.id, "Overview,Genres,MediaSources,MediaStreams")
        val episodes = if (item.optString("Type").equals("Movie", true)) {
            listOf(Episode(anime.id, 1, anime.title))
        } else {
            val response = JSONObject(SourceHttp.get(url(source, "/Shows/${anime.id}/Episodes", mapOf(
                "userId" to source.userId, "fields" to "Overview,MediaSources,MediaStreams",
                "enableImages" to "false", "limit" to "1000"
            )), headers = auth(source)))
            response.optJSONArray("Items").objects().mapIndexed { index, episode ->
                Episode(
                    id = episode.getString("Id"), number = episode.optInt("IndexNumber", index + 1),
                    title = episode.optString("Name"), seasonNumber = episode.optInt("ParentIndexNumber")
                )
            }
        }
        return AnimeDetails(
            anime = mapAnime(item, source), description = item.optString("Overview"),
            status = listOfNotNull(item.optInt("ProductionYear").takeIf { it > 0 }?.toString(), item.optString("Type").takeIf(String::isNotBlank)).joinToString(" • "),
            genres = item.optJSONArray("Genres").strings().take(8), episodes = episodes,
            sourceNotice = "This title is served from your private Jellyfin library. Original files retain their embedded audio and subtitle tracks."
        )
    }

    override suspend fun languages(episode: Episode, source: SourceDefinition): List<LanguageOption> =
        listOf(LanguageOption("mul", "All embedded tracks", episode.id, "All tracks"))

    override suspend fun qualities(language: LanguageOption, source: SourceDefinition): List<QualityOption> {
        val item = item(source, language.embedUrl, "MediaSources,MediaStreams")
        val mediaSource = item.optJSONArray("MediaSources")?.optJSONObject(0)
        val size = mediaSource?.optLong("Size")?.takeIf { it > 0 } ?: item.optLong("Size")
        val container = mediaSource?.optString("Container").orEmpty().substringBefore(',').ifBlank { "mkv" }
        val token = encode(source.authToken)
        val mediaSourceId = mediaSource?.optString("Id").orEmpty()
        val directUrl = buildString {
            append(source.baseUrl.trimEnd('/')).append("/Items/").append(language.embedUrl).append("/Download?api_key=").append(token)
            if (mediaSourceId.isNotBlank()) append("&mediaSourceId=").append(encode(mediaSourceId))
        }
        return listOf(QualityOption("Original", directUrl, size, delivery = DeliveryKind.DIRECT, container = container))
    }

    fun validate(baseUrl: String, token: String): JellyfinConnection {
        val temporary = SourceDefinition("validation", "Jellyfin", baseUrl.trim().trimEnd('/'), kind = SourceKind.JELLYFIN, authToken = token.trim())
        val publicInfo = JSONObject(SourceHttp.get(url(temporary, "/System/Info/Public")))
        val user = runCatching { JSONObject(SourceHttp.get(url(temporary, "/Users/Me"), headers = auth(temporary))) }.getOrNull()
            ?: run {
                val users = JSONArray(SourceHttp.get(url(temporary, "/Users"), headers = auth(temporary)))
                users.optJSONObject(0) ?: error("The token is valid, but no Jellyfin user is available")
            }
        return JellyfinConnection(publicInfo.optString("ServerName", "Jellyfin"), user.getString("Id"))
    }

    private fun items(source: SourceDefinition, search: String?): List<Anime> {
        val params = linkedMapOf(
            "userId" to source.userId, "includeItemTypes" to "Series,Movie", "recursive" to "true",
            "fields" to "Overview,Genres,CommunityRating,PrimaryImageAspectRatio", "enableImages" to "true",
            "imageTypeLimit" to "1", "limit" to "200", "sortBy" to if (search == null) "DateLastContentAdded" else "SortName",
            "sortOrder" to "Descending"
        )
        if (search != null) params["searchTerm"] = search
        val response = runCatching { JSONObject(SourceHttp.get(url(source, "/Items", params), headers = auth(source))) }
            .getOrElse {
                val legacy = url(source, "/Users/${source.userId}/Items", params - "userId")
                JSONObject(SourceHttp.get(legacy, headers = auth(source)))
            }
        return response.optJSONArray("Items").objects().map { mapAnime(it, source) }
    }

    private fun item(source: SourceDefinition, id: String, fields: String): JSONObject = runCatching {
        JSONObject(SourceHttp.get(url(source, "/Items/$id", mapOf("userId" to source.userId, "fields" to fields)), headers = auth(source)))
    }.getOrElse {
        JSONObject(SourceHttp.get(url(source, "/Users/${source.userId}/Items/$id", mapOf("fields" to fields)), headers = auth(source)))
    }

    private fun mapAnime(item: JSONObject, source: SourceDefinition): Anime {
        val id = item.getString("Id")
        val hasImage = item.optJSONObject("ImageTags")?.has("Primary") == true || item.has("PrimaryImageTag")
        val image = if (hasImage) "${source.baseUrl}/Items/$id/Images/Primary?maxWidth=700&quality=90#kairo-source=${source.id}" else ""
        val score = item.optDouble("CommunityRating").takeIf { !it.isNaN() && it > 0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
        return Anime(id, item.optString("Name", "Untitled"), image, "jellyfin://$id", score, item.optString("Type", "MEDIA"), source.id)
    }

    private fun auth(source: SourceDefinition) = mapOf("X-Emby-Token" to source.authToken)

    private fun url(source: SourceDefinition, path: String, params: Map<String, String> = emptyMap()): String = buildString {
        append(source.baseUrl.trimEnd('/')).append(path)
        val useful = params.filterValues(String::isNotBlank)
        if (useful.isNotEmpty()) append(useful.entries.joinToString("&", prefix = "?") { "${encode(it.key)}=${encode(it.value)}" })
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }.filter(String::isNotBlank)
}
