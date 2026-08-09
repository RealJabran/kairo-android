package app.kairo.anime.data.captions

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class OnlineSubtitle(
    val fileId: String,
    val language: String,
    val release: String,
    val url: String
)

/** Credential-free client for Stremio's official OpenSubtitles v3 add-on. */
class OpenSubtitlesClient(private val context: Context) {
    suspend fun search(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        language: String
    ): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        val video = resolveVideo(title, seasonNumber, episodeNumber)
            ?: error("No matching title was found in the public subtitle catalog")
        val response = getJson("$SUBTITLE_ADDON/subtitles/${video.type}/${video.videoId}.json")
        val subtitles = response.optJSONArray("subtitles") ?: return@withContext emptyList()
        val preferred = languageCode(language)
        buildList {
            repeat(subtitles.length()) { index ->
                val item = subtitles.optJSONObject(index) ?: return@repeat
                val url = item.optString("url").takeIf { it.startsWith("https://") } ?: return@repeat
                val code = item.optString("lang", "und")
                add(
                    OnlineSubtitle(
                        fileId = item.optString("id").ifBlank { "$index" },
                        language = languageName(code),
                        release = "${languageName(code)} caption ${index + 1}",
                        url = url
                    )
                )
            }
        }.distinctBy(OnlineSubtitle::url)
            .filter { languageCode(it.language) == preferred }
            .take(50)
    }

    suspend fun downloadToCache(subtitle: OnlineSubtitle): Uri = withContext(Dispatchers.IO) {
        val bytes = unpackSubtitle(downloadBytes(subtitle))
        val directory = File(context.cacheDir, "online-captions").apply { mkdirs() }
        val safeId = subtitle.fileId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        require(text.isNotBlank()) { "The downloaded caption file is empty" }
        val extension = when {
            text.trimStart().startsWith("WEBVTT", true) -> "vtt"
            text.contains("[Script Info]", true) -> "ass"
            else -> "srt"
        }
        val output = File(directory, "$safeId.$extension")
        output.outputStream().use { it.write(bytes) }
        Uri.fromFile(output)
    }

    suspend fun downloadBest(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        language: String
    ): Pair<OnlineSubtitle, ByteArray>? = withContext(Dispatchers.IO) {
        val result = search(title, seasonNumber, episodeNumber, language).firstOrNull() ?: return@withContext null
        result to downloadBytes(result)
    }

    private fun resolveVideo(title: String, seasonNumber: Int, episodeNumber: Int): StremioVideo? {
        val isEpisode = episodeNumber > 0
        val types = if (isEpisode) listOf("series", "movie") else listOf("movie", "series")
        val candidates = types.flatMap { type ->
            val url = "$CINEMETA/catalog/$type/top/search=${encodePath(title)}.json"
            val metas = runCatching { getJson(url).optJSONArray("metas") }.getOrNull() ?: return@flatMap emptyList()
            buildList {
                repeat(metas.length()) { index ->
                    val item = metas.optJSONObject(index) ?: return@repeat
                    add(StremioCandidate(item.optString("id"), item.optString("name"), item.optString("type", type)))
                }
            }
        }
        val normalizedTitle = normalize(title)
        val candidate = candidates
            .filter { it.id.startsWith("tt") }
            .minByOrNull { candidate -> titleDistance(normalizedTitle, normalize(candidate.name)) }
            ?: return null
        if (candidate.type == "movie" || !isEpisode) return StremioVideo("movie", candidate.id)

        val meta = getJson("$CINEMETA/meta/series/${candidate.id}.json").optJSONObject("meta")
        val videos = meta?.optJSONArray("videos")
        if (videos != null) {
            val parsed = buildList {
                repeat(videos.length()) { index ->
                    val item = videos.optJSONObject(index) ?: return@repeat
                    add(StremioEpisode(item.optString("id"), item.optInt("season"), item.optInt("episode")))
                }
            }.filter { it.id.isNotBlank() }
            val exact = if (seasonNumber > 0) parsed.firstOrNull { it.season == seasonNumber && it.episode == episodeNumber } else null
            val absolute = if (seasonNumber <= 0) parsed.filter { it.season > 0 }.getOrNull(episodeNumber - 1) else null
            val fallback = parsed.firstOrNull { it.episode == episodeNumber && it.season > 0 }
            (exact ?: absolute ?: fallback)?.let { return StremioVideo("series", it.id) }
        }
        return StremioVideo("series", "${candidate.id}:${seasonNumber.coerceAtLeast(1)}:$episodeNumber")
    }

    private fun downloadBytes(subtitle: OnlineSubtitle): ByteArray {
        val connection = connection(subtitle.url)
        val code = connection.responseCode
        if (code !in 200..299) error("Caption download returned HTTP $code")
        if (connection.contentLengthLong > MAX_CAPTION_BYTES) error("The caption file is unexpectedly large")
        return connection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_CAPTION_BYTES) error("The caption file is unexpectedly large")
            bytes
        }
    }

    private fun unpackSubtitle(bytes: ByteArray): ByteArray = when {
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() ->
            GZIPInputStream(ByteArrayInputStream(bytes)).use(::readLimited)
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entry.isDirectory) entry = zip.nextEntry
                require(entry != null) { "The caption archive is empty" }
                readLimited(zip)
            }
        else -> bytes
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_CAPTION_BYTES) { "The caption file is unexpectedly large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun getJson(url: String): JSONObject {
        val connection = connection(url)
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("Public subtitle service returned HTTP $code")
        return JSONObject(response)
    }

    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 25_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json, text/plain, */*")
    }

    private fun titleDistance(expected: String, actual: String): Int = when {
        expected == actual -> 0
        actual.startsWith(expected) || expected.startsWith(actual) -> 1
        actual.contains(expected) || expected.contains(actual) -> 2
        else -> 10 + kotlin.math.abs(expected.length - actual.length)
    }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "").trim()

    private data class StremioCandidate(val id: String, val name: String, val type: String)
    private data class StremioVideo(val type: String, val videoId: String)
    private data class StremioEpisode(val id: String, val season: Int, val episode: Int)

    companion object {
        private const val CINEMETA = "https://v3-cinemeta.strem.io"
        private const val SUBTITLE_ADDON = "https://opensubtitles-v3.strem.io"
        private const val USER_AGENT = "Kairo v2.0.0"
        private const val MAX_CAPTION_BYTES = 5 * 1024 * 1024

        fun languageCode(language: String): String = when (language.lowercase().take(3)) {
            "eng" -> "eng"
            "hin" -> "hin"
            "urd" -> "urd"
            "ara" -> "ara"
            "jap", "jpn" -> "jpn"
            "spa" -> "spa"
            "fre", "fra" -> "fra"
            "ger", "deu" -> "deu"
            "por" -> "por"
            else -> language.lowercase().take(3)
        }

        private fun languageName(code: String): String = when (code.lowercase()) {
            "eng", "en" -> "English"
            "hin", "hi" -> "Hindi"
            "urd", "ur" -> "Urdu"
            "ara", "ar" -> "Arabic"
            "jpn", "ja" -> "Japanese"
            "spa", "es" -> "Spanish"
            "fre", "fra", "fr" -> "French"
            "ger", "deu", "de" -> "German"
            "por", "pt" -> "Portuguese"
            else -> code.uppercase()
        }

        private fun encodePath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
