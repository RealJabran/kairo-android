package app.kairo.anime.data

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.kairo.anime.download.AnimeDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.Locale

class KairoRepository(private val context: Context) {
    val preferences = KairoPreferences(context)

    fun selectedSource(): SourceDefinition = preferences.sources().firstOrNull { it.id == preferences.selectedSourceId }
        ?: preferences.sources().first()

    suspend fun browse(source: SourceDefinition = selectedSource()): List<Anime> = withContext(Dispatchers.IO) {
        val document = getDocument("${source.baseUrl}/browse?sort=order_top_airing&status=Currently+Airing", source.baseUrl)
        parseAnimeCards(document, source)
    }

    suspend fun search(query: String, source: SourceDefinition = selectedSource()): List<Anime> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext browse(source)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = getDocument("${source.baseUrl}/search/suggestions?q=$encoded", source.baseUrl)
        parseAnimeCards(document, source)
    }

    suspend fun details(anime: Anime): AnimeDetails = withContext(Dispatchers.IO) {
        val source = preferences.sources().firstOrNull { it.id == anime.sourceId } ?: selectedSource()
        val document = getDocument(anime.url, source.baseUrl)
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.select("[data-synopsis], .synopsis, .description").firstOrNull()?.text().orEmpty()
        val status = document.select(".badge, [data-status]").firstOrNull { it.text().contains("air", true) || it.text().contains("finish", true) }?.text().orEmpty()
        val genres = document.select("a[href*=/genre/], a[href*='genres=']").map { it.text() }.filter { it.isNotBlank() }.distinct().take(6)
        val episodes = episodes(anime.id, source)
        AnimeDetails(anime, description, status, genres, episodes)
    }

    suspend fun episodes(animeId: String, source: SourceDefinition = selectedSource()): List<Episode> = withContext(Dispatchers.IO) {
        val json = JSONObject(getText("${source.baseUrl}/api/frontend/anime/$animeId/episodes", source.baseUrl))
        val array = json.getJSONArray("episodes")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Episode(item.getInt("id"), item.optInt("number", index + 1), item.optString("title"), item.optBoolean("filler"))
        }
    }

    suspend fun languages(episodeId: Int, source: SourceDefinition = selectedSource()): List<LanguageOption> = withContext(Dispatchers.IO) {
        val json = JSONObject(getText("${source.baseUrl}/api/frontend/episode/$episodeId/languages", source.baseUrl))
        val array = json.getJSONArray("languages")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            LanguageOption(item.optString("code", "jpn"), item.optString("name", "Japanese"), item.optString("embed_url"))
        }.distinctBy { it.code + it.embedUrl }
    }

    suspend fun qualities(language: LanguageOption, source: SourceDefinition = selectedSource()): List<QualityOption> = withContext(Dispatchers.IO) {
        val embedHtml = getText(resolveUrl(language.embedUrl, source.baseUrl), source.baseUrl)
        val streamUrl = Regex("(?:file|source)\\s*[:=]\\s*['\"`]([^'\"`]+\\.m3u8[^'\"`]*)", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.groupValues?.get(1)
            ?: Regex("https?[^'\"\\s]+\\.m3u8[^'\"\\s]*", RegexOption.IGNORE_CASE).find(embedHtml)?.value
            ?: if (language.embedUrl.contains("m3u8")) language.embedUrl else error("No playable stream was found")
        val absoluteStream = resolveUrl(streamUrl.replace("\\/", "/"), language.embedUrl)
        val playlist = getText(absoluteStream, source.baseUrl)
        val variants = parseMasterPlaylist(playlist, absoluteStream)
        if (variants.isEmpty()) {
            listOf(QualityOption("Auto", absoluteStream, estimateSize(playlist, 0)))
        } else {
            variants.map { variant ->
                val mediaPlaylist = runCatching { getText(variant.url, source.baseUrl) }.getOrDefault("")
                QualityOption(
                    label = variant.label,
                    url = variant.url,
                    estimatedBytes = estimateSize(mediaPlaylist, variant.bandwidth),
                    bandwidthBitsPerSecond = variant.bandwidth
                )
            }.sortedBy { it.label.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
        }
    }

    suspend fun validateSource(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { getDocument("${baseUrl.trimEnd('/')}/browse", baseUrl).select("a[href*=/anime/]").isNotEmpty() }.getOrDefault(false)
    }

    fun enqueueDownload(anime: Anime, episode: Episode, language: LanguageOption, quality: QualityOption): UUID {
        val source = preferences.sources().firstOrNull { it.id == anime.sourceId } ?: selectedSource()
        val input = Data.Builder()
            .putString("animeTitle", anime.title)
            .putString("episodeLabel", "Episode ${episode.number}")
            .putString("language", language.label)
            .putString("quality", quality.label)
            .putString("playlistUrl", quality.url)
            .putString("sourceBase", source.baseUrl)
            .putLong("estimatedBytes", quality.estimatedBytes)
            .putString("animeId", anime.id)
            .putString("animeImageUrl", anime.imageUrl)
            .putString("animeUrl", anime.url)
            .putString("sourceId", anime.sourceId)
            .putInt("episodeNumber", episode.number)
            .build()
        val request = OneTimeWorkRequestBuilder<AnimeDownloadWorker>().setInputData(input).addTag("kairo_downloads").build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }

    fun removeDownload(record: DownloadRecord, deleteFile: Boolean) {
        if (deleteFile) runCatching { context.contentResolver.delete(Uri.parse(record.uri), null, null) }
        preferences.removeDownload(record.id)
    }

    private fun parseAnimeCards(document: Document, source: SourceDefinition): List<Anime> {
        val anchors = document.select("a[href*=/anime/]")
        return anchors.mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { resolveUrl(anchor.attr("href"), source.baseUrl) }
            val id = Regex("-(\\d+)(?:[/?#]|$)").find(href)?.groupValues?.get(1)
                ?: Regex("/anime/(\\d+)").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val imageUrl = image?.absUrl("src")?.ifBlank { resolveUrl(image.attr("src"), source.baseUrl) }.orEmpty()
            val title = image?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: anchor.select("p, h2, h3").firstOrNull { it.text().isNotBlank() }?.text()
                ?: anchor.attr("title")
            if (title.isBlank() || imageUrl.isBlank()) return@mapNotNull null
            val text = anchor.text()
            val score = Regex("\\b([0-9]\\.[0-9])\\b").find(text)?.value.orEmpty()
            Anime(id, title.trim(), imageUrl, href, score, sourceId = source.id)
        }.distinctBy { it.id }.take(80)
    }

    private data class Variant(val label: String, val url: String, val bandwidth: Long)

    private fun parseMasterPlaylist(text: String, playlistUrl: String): List<Variant> {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val result = mutableListOf<Variant>()
        lines.forEachIndexed { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@forEachIndexed
                val resolution = Regex("RESOLUTION=([0-9]+x[0-9]+)").find(line)?.groupValues?.get(1)?.substringAfter('x')?.plus("p")
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val bandwidthLabel = bandwidth.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) }
                result += Variant(resolution ?: bandwidthLabel ?: "Auto", resolveUrl(next, playlistUrl), bandwidth)
            }
        }
        return result.distinctBy { it.label }
    }

    private fun estimateSize(mediaPlaylist: String, bandwidth: Long): Long {
        if (mediaPlaylist.isBlank() || bandwidth <= 0) return 0
        val durationSeconds = mediaPlaylist.lineSequence()
            .filter { it.startsWith("#EXTINF:") }
            .sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 }
        return if (durationSeconds <= 0) 0 else (durationSeconds * bandwidth / 8.0 * 1.03).toLong()
    }

    private fun getDocument(url: String, referer: String): Document = Jsoup.connect(url)
        .userAgent(USER_AGENT).referrer(referer).timeout(30_000).followRedirects(true).get()

    internal fun getText(url: String, referer: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept", "*/*")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 Kairo/1.0"

        fun resolveUrl(value: String, base: String): String {
            if (value.startsWith("http://") || value.startsWith("https://")) return value
            return URL(URL(base), value).toString()
        }
    }
}
