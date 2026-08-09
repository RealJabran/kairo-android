package app.kairo.anime.data.source

import app.kairo.anime.data.KairoRepository
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL

internal object SourceHttp {
    fun document(url: String, referer: String): Document = Jsoup.connect(url)
        .userAgent(KairoRepository.USER_AGENT).referrer(referer).timeout(30_000).followRedirects(true).get()

    fun get(
        url: String,
        referer: String = url,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30_000
    ): String = request(url, "GET", referer, headers, null, timeoutMs)

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        request(url, "POST", url, headers + ("Content-Type" to "application/json"), body, 30_000)

    private fun request(
        url: String,
        method: String,
        referer: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.requestMethod = method
        connection.setRequestProperty("User-Agent", KairoRepository.USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        headers.forEach(connection::setRequestProperty)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(body) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("Source returned HTTP $code${response.take(180).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        return response
    }
}
