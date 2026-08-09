package app.kairo.anime.data.source

import app.kairo.anime.data.*
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class AniListAdapter : AnimeSourceAdapter {
    override val kind = SourceKind.ANILIST

    override suspend fun browse(source: SourceDefinition): List<Anime> = queryMedia(null, source)

    override suspend fun search(query: String, source: SourceDefinition): List<Anime> =
        if (query.isBlank()) browse(source) else queryMedia(query, source)

    override suspend fun details(anime: Anime, source: SourceDefinition): AnimeDetails {
        val query = """
            query (${DOLLAR}id: Int!) {
              Media(id: ${DOLLAR}id, type: ANIME) {
                id title { romaji english native } coverImage { extraLarge large }
                averageScore format description(asHtml: false) status genres episodes siteUrl
              }
            }
        """.trimIndent()
        val media = graphQl(query, JSONObject().put("id", anime.id.toInt())).getJSONObject("Media")
        val mapped = mapAnime(media, source)
        return AnimeDetails(
            anime = mapped,
            description = cleanText(media.optNullableString("description")),
            status = media.optNullableString("status").replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase),
            genres = media.optJSONArray("genres").strings().take(8),
            episodes = emptyList(),
            sourceNotice = buildString {
                append("AniList is a discovery and tracking catalog; it does not host video files. ")
                media.optInt("episodes").takeIf { it > 0 }?.let { append("This title lists $it episodes. ") }
                append("Use Find playable source to search your streaming and personal-library providers.")
            }
        )
    }

    override suspend fun languages(episode: Episode, source: SourceDefinition): List<LanguageOption> =
        error("AniList provides metadata, not playable episodes")

    override suspend fun qualities(language: LanguageOption, source: SourceDefinition): List<QualityOption> =
        error("AniList provides metadata, not video qualities")

    private fun queryMedia(search: String?, source: SourceDefinition): List<Anime> {
        val query = """
            query (${DOLLAR}search: String) {
              Page(page: 1, perPage: 50) {
                media(type: ANIME, search: ${DOLLAR}search, sort: [TRENDING_DESC, POPULARITY_DESC], isAdult: false) {
                  id title { romaji english native } coverImage { extraLarge large }
                  averageScore format siteUrl
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject().apply { if (search != null) put("search", search) else put("search", JSONObject.NULL) }
        val array = graphQl(query, variables).getJSONObject("Page").getJSONArray("media")
        return List(array.length()) { mapAnime(array.getJSONObject(it), source) }
    }

    private fun graphQl(query: String, variables: JSONObject): JSONObject {
        val response = JSONObject(SourceHttp.postJson(ENDPOINT, JSONObject().put("query", query).put("variables", variables).toString()))
        response.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { errors ->
            error(errors.optJSONObject(0)?.optString("message") ?: "AniList request failed")
        }
        return response.getJSONObject("data")
    }

    private fun mapAnime(item: JSONObject, source: SourceDefinition): Anime {
        val titleObject = item.getJSONObject("title")
        val title = titleObject.optNullableString("english").ifBlank { titleObject.optNullableString("romaji") }
            .ifBlank { titleObject.optNullableString("native") }.ifBlank { "Untitled anime" }
        val covers = item.optJSONObject("coverImage")
        val score = item.optInt("averageScore").takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it / 10.0) }.orEmpty()
        return Anime(
            id = item.getInt("id").toString(), title = title,
            imageUrl = covers?.optNullableString("extraLarge").orEmpty().ifBlank { covers?.optNullableString("large").orEmpty() },
            url = item.optNullableString("siteUrl"), score = score,
            type = item.optNullableString("format").replace('_', ' ').ifBlank { "ANIME" }, sourceId = source.id
        )
    }

    private fun cleanText(value: String): String = Jsoup.parse(value).text()

    private fun JSONObject.optNullableString(key: String): String =
        if (isNull(key)) "" else optString(key).takeUnless { it == "null" }.orEmpty()

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }.filter(String::isNotBlank)

    companion object {
        private const val ENDPOINT = "https://graphql.anilist.co"
        private const val DOLLAR = '$'
    }
}
