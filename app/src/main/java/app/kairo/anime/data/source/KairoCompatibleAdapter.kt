package app.kairo.anime.data.source

import app.kairo.anime.data.*
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class KairoCompatibleAdapter : AnimeSourceAdapter {
    override val kind = SourceKind.KAIRO_COMPATIBLE

    override suspend fun browse(source: SourceDefinition): List<Anime> {
        val document = SourceHttp.document("${source.baseUrl}/browse?sort=order_top_airing&status=Currently+Airing", source.baseUrl)
        return parseAnimeCards(document, source)
    }

    override suspend fun search(query: String, source: SourceDefinition): List<Anime> {
        if (query.isBlank()) return browse(source)
        val encoded = URLEncoder.encode(query, "UTF-8")
        return parseAnimeCards(SourceHttp.document("${source.baseUrl}/search/suggestions?q=$encoded", source.baseUrl), source)
    }

    override suspend fun details(anime: Anime, source: SourceDefinition): AnimeDetails {
        val document = SourceHttp.document(anime.url, source.baseUrl)
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.select("[data-synopsis], .synopsis, .description").firstOrNull()?.text().orEmpty()
        val status = document.select(".badge, [data-status]").firstOrNull {
            it.text().contains("air", true) || it.text().contains("finish", true)
        }?.text().orEmpty()
        val genres = document.select("a[href*=/genre/], a[href*='genres=']").map { it.text() }
            .filter(String::isNotBlank).distinct().take(6)
        return AnimeDetails(anime, description, status, genres, episodes(anime.id, source))
    }

    private fun episodes(animeId: String, source: SourceDefinition): List<Episode> {
        val json = JSONObject(SourceHttp.get("${source.baseUrl}/api/frontend/anime/$animeId/episodes", source.baseUrl))
        val array = json.getJSONArray("episodes")
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Episode(item.get("id").toString(), item.optInt("number", index + 1), item.optString("title"), item.optBoolean("filler"))
        }
    }

    override suspend fun languages(episode: Episode, source: SourceDefinition): List<LanguageOption> {
        val json = JSONObject(SourceHttp.get("${source.baseUrl}/api/frontend/episode/${episode.id}/languages", source.baseUrl))
        val array = json.getJSONArray("languages")
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            LanguageOption(item.optString("code", "jpn"), item.optString("name", "Japanese"), item.optString("embed_url"))
        }.distinctBy { it.code + it.embedUrl }
    }

    override suspend fun qualities(language: LanguageOption, source: SourceDefinition): List<QualityOption> {
        val embedHtml = SourceHttp.get(KairoRepository.resolveUrl(language.embedUrl, source.baseUrl), source.baseUrl)
        val streamUrl = Regex("(?:file|source)\\s*[:=]\\s*['\"`]([^'\"`]+\\.m3u8[^'\"`]*)", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.groupValues?.get(1)
            ?: Regex("https?[^'\"\\s]+\\.m3u8[^'\"\\s]*", RegexOption.IGNORE_CASE).find(embedHtml)?.value
            ?: if (language.embedUrl.contains("m3u8")) language.embedUrl else error("No playable stream was found")
        val absoluteStream = KairoRepository.resolveUrl(streamUrl.replace("\\/", "/"), language.embedUrl)
        val playlist = SourceHttp.get(absoluteStream, source.baseUrl)
        val variants = parseMasterPlaylist(playlist, absoluteStream)
        if (variants.isEmpty()) return listOf(QualityOption("Auto", absoluteStream))
        val fixed = variants.map { variant ->
            val mediaPlaylist = runCatching { SourceHttp.get(variant.url, source.baseUrl) }.getOrDefault("")
            QualityOption(variant.label, variant.url, estimateSize(mediaPlaylist, variant.bandwidth), variant.bandwidth)
        }.sortedBy { it.label.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
        return listOf(QualityOption("Auto", absoluteStream)) + fixed
    }

    fun validate(baseUrl: String): Boolean = runCatching {
        SourceHttp.document("${baseUrl.trimEnd('/')}/browse", baseUrl).select("a[href*=/anime/]").isNotEmpty()
    }.getOrDefault(false)

    private fun parseAnimeCards(document: Document, source: SourceDefinition): List<Anime> {
        return document.select("a[href*=/anime/]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { KairoRepository.resolveUrl(anchor.attr("href"), source.baseUrl) }
            val id = Regex("-(\\d+)(?:[/?#]|$)").find(href)?.groupValues?.get(1)
                ?: Regex("/anime/(\\d+)").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val imageUrl = image?.absUrl("src")?.ifBlank { KairoRepository.resolveUrl(image.attr("src"), source.baseUrl) }.orEmpty()
            val title = image?.attr("alt")?.takeIf(String::isNotBlank)
                ?: anchor.select("p, h2, h3").firstOrNull { it.text().isNotBlank() }?.text()
                ?: anchor.attr("title")
            if (title.isBlank() || imageUrl.isBlank()) return@mapNotNull null
            val score = Regex("\\b([0-9]\\.[0-9])\\b").find(anchor.text())?.value.orEmpty()
            Anime(id, title.trim(), imageUrl, href, score, sourceId = source.id)
        }.distinctBy(Anime::id).take(80)
    }

    private data class Variant(val label: String, val url: String, val bandwidth: Long)

    private fun parseMasterPlaylist(text: String, playlistUrl: String): List<Variant> {
        val lines = text.lineSequence().map(String::trim).toList()
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            val resolution = Regex("RESOLUTION=([0-9]+x[0-9]+)").find(line)?.groupValues?.get(1)?.substringAfter('x')?.plus("p")
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val label = resolution ?: bandwidth.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) } ?: "Auto"
            Variant(label, KairoRepository.resolveUrl(next, playlistUrl), bandwidth)
        }.distinctBy(Variant::label)
    }

    private fun estimateSize(mediaPlaylist: String, bandwidth: Long): Long {
        if (mediaPlaylist.isBlank() || bandwidth <= 0) return 0
        val seconds = mediaPlaylist.lineSequence().filter { it.startsWith("#EXTINF:") }
            .sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 }
        return if (seconds <= 0) 0 else (seconds * bandwidth / 8.0 * 1.03).toLong()
    }
}
