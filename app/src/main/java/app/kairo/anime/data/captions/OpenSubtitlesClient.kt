package app.kairo.anime.data.captions

import android.content.Context
import android.net.Uri
import app.kairo.anime.data.KairoPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OnlineSubtitle(
    val fileId: Long,
    val language: String,
    val release: String,
    val downloads: Int,
    val hearingImpaired: Boolean
)

data class OpenSubtitlesSession(val token: String, val baseUrl: String)

class OpenSubtitlesClient(private val context: Context) {
    private val preferences = KairoPreferences(context)

    suspend fun login(apiKey: String, username: String, password: String): OpenSubtitlesSession = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank() && username.isNotBlank() && password.isNotBlank()) { "Complete every OpenSubtitles field" }
        val response = requestJson(
            path = "/login",
            method = "POST",
            apiKey = apiKey.trim(),
            token = "",
            body = JSONObject().put("username", username.trim()).put("password", password).toString()
        )
        val token = response.optString("token").takeIf(String::isNotBlank)
            ?: error("OpenSubtitles did not return an access token")
        val returnedHost = response.optString("base_url", "api.opensubtitles.com").trim().trimEnd('/')
        val returnedBase = (if (returnedHost.startsWith("http")) returnedHost else "https://$returnedHost")
            .let { if (it.endsWith("/api/v1")) it else "$it/api/v1" }
        OpenSubtitlesSession(token, returnedBase)
    }

    suspend fun search(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        language: String
    ): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        ensureConfigured()
        val params = linkedMapOf(
            "query" to title,
            "languages" to languageCode(language),
            "order_by" to "download_count",
            "order_direction" to "desc"
        )
        if (seasonNumber > 0) params["season_number"] = seasonNumber.toString()
        if (episodeNumber > 0) params["episode_number"] = episodeNumber.toString()
        val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val response = requestJson("/subtitles?$query", apiKey = preferences.openSubtitlesApiKey, token = preferences.openSubtitlesToken, baseUrl = preferences.openSubtitlesBaseUrl)
        val data = response.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            repeat(data.length()) { index ->
                val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: return@repeat
                val file = attributes.optJSONArray("files")?.optJSONObject(0) ?: return@repeat
                val fileId = file.optLong("file_id").takeIf { it > 0 } ?: return@repeat
                add(
                    OnlineSubtitle(
                        fileId = fileId,
                        language = attributes.optString("language", language),
                        release = attributes.optString("release").ifBlank { file.optString("file_name", "Subtitle") },
                        downloads = attributes.optInt("download_count"),
                        hearingImpaired = attributes.optBoolean("hearing_impaired")
                    )
                )
            }
        }.distinctBy(OnlineSubtitle::fileId).take(30)
    }

    suspend fun downloadToCache(subtitle: OnlineSubtitle): Uri = withContext(Dispatchers.IO) {
        val bytes = downloadBytes(subtitle)
        val directory = File(context.cacheDir, "online-captions").apply { mkdirs() }
        val output = File(directory, "${subtitle.fileId}.srt")
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

    private fun downloadBytes(subtitle: OnlineSubtitle): ByteArray {
        ensureConfigured()
        val response = requestJson(
            path = "/download", method = "POST",
            apiKey = preferences.openSubtitlesApiKey, token = preferences.openSubtitlesToken,
            body = JSONObject().put("file_id", subtitle.fileId).put("sub_format", "srt").toString(),
            baseUrl = preferences.openSubtitlesBaseUrl
        )
        val link = response.optString("link").takeIf(String::isNotBlank) ?: error("OpenSubtitles did not provide a download link")
        val connection = (URL(link).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val code = connection.responseCode
        if (code !in 200..299) error("Caption download returned HTTP $code")
        val length = connection.contentLengthLong
        if (length > MAX_CAPTION_BYTES) error("The caption file is unexpectedly large")
        return connection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_CAPTION_BYTES) error("The caption file is unexpectedly large")
            bytes
        }
    }

    private fun ensureConfigured() {
        if (preferences.openSubtitlesApiKey.isBlank() || preferences.openSubtitlesToken.isBlank()) {
            error("Connect OpenSubtitles in Kairo Settings first")
        }
    }

    private fun requestJson(
        path: String,
        method: String = "GET",
        apiKey: String,
        token: String,
        body: String? = null,
        baseUrl: String = DEFAULT_BASE_URL
    ): JSONObject {
        val connection = (URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            requestMethod = method
            instanceFollowRedirects = true
            setRequestProperty("Api-Key", apiKey)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.bufferedWriter().use { it.write(body) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(response).optString("message") }.getOrNull().orEmpty()
            if (code == 401 || code == 403) error("OpenSubtitles connection expired. Reconnect it in Settings.")
            error(message.ifBlank { "OpenSubtitles returned HTTP $code" })
        }
        return JSONObject(response)
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.opensubtitles.com/api/v1"
        private const val USER_AGENT = "Kairo v1.4.0"
        private const val MAX_CAPTION_BYTES = 5 * 1024 * 1024

        fun languageCode(language: String): String = when (language.lowercase()) {
            "english" -> "en"
            "hindi" -> "hi"
            "urdu" -> "ur"
            "arabic" -> "ar"
            "japanese" -> "ja"
            "spanish" -> "es"
            "french" -> "fr"
            "german" -> "de"
            "portuguese" -> "pt"
            else -> language.lowercase().take(2)
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
