package app.kairo.anime.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kairo.anime.R
import app.kairo.anime.data.DownloadRecord
import app.kairo.anime.data.DeliveryKind
import app.kairo.anime.data.KairoPreferences
import app.kairo.anime.data.KairoRepository
import app.kairo.anime.data.captions.OpenSubtitlesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val preferences = KairoPreferences(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val animeTitle = inputData.getString("animeTitle") ?: return@withContext Result.failure()
        val episodeLabel = inputData.getString("episodeLabel") ?: return@withContext Result.failure()
        val language = inputData.getString("language").orEmpty()
        val quality = inputData.getString("quality").orEmpty()
        val playlistUrl = inputData.getString("playlistUrl") ?: return@withContext Result.failure()
        val sourceBase = inputData.getString("sourceBase") ?: playlistUrl
        val estimatedBytes = inputData.getLong("estimatedBytes", 0)
        val delivery = runCatching { DeliveryKind.valueOf(inputData.getString("delivery") ?: DeliveryKind.HLS.name) }.getOrDefault(DeliveryKind.HLS)
        val container = sanitize(inputData.getString("container").orEmpty().ifBlank { "mp4" }).lowercase()
        val authToken = inputData.getString("authToken").orEmpty()
        val tree = preferences.downloadTreeUri?.let(Uri::parse) ?: return@withContext Result.failure(workDataOf("error" to "Choose a download folder in Settings"))

        setForeground(foregroundInfo(animeTitle, episodeLabel, 0))
        setProgress(progressData(animeTitle, episodeLabel, 0, 0, estimatedBytes, 0, delivery == DeliveryKind.HLS))

        var partialUri: Uri? = null
        runCatching {
            val root = DocumentFile.fromTreeUri(applicationContext, tree) ?: error("Download folder is unavailable")
            val animeDir = root.findFile(sanitize(animeTitle)) ?: root.createDirectory(sanitize(animeTitle)) ?: error("Cannot create anime folder")
            val outputName = "${sanitize(episodeLabel)} • ${sanitize(language)} • ${sanitize(quality)}.$container"
            animeDir.findFile(outputName)?.delete()
            val output = animeDir.createFile(videoMime(container), outputName) ?: error("Cannot create output file")
            partialUri = output.uri
            val written = applicationContext.contentResolver.openOutputStream(output.uri, "w")!!.use { destination ->
                when (delivery) {
                    DeliveryKind.DIRECT -> downloadDirect(playlistUrl, sourceBase, authToken, destination, animeTitle, episodeLabel, estimatedBytes)
                    DeliveryKind.HLS -> downloadHls(playlistUrl, sourceBase, destination, animeTitle, episodeLabel, estimatedBytes)
                    DeliveryKind.LOCAL -> error("Local files do not need to be downloaded")
                }
            }
            var subtitleUri = ""
            if (preferences.autoDownloadCaptions && preferences.openSubtitlesConnected) {
                runCatching {
                    val match = OpenSubtitlesClient(applicationContext).downloadBest(
                        title = animeTitle,
                        seasonNumber = inputData.getInt("seasonNumber", 0),
                        episodeNumber = inputData.getInt("episodeNumber", 0),
                        language = preferences.subtitleLanguage
                    )
                    if (match != null) {
                        val captionName = outputName.substringBeforeLast('.') + " • ${sanitize(preferences.subtitleLanguage)}.srt"
                        animeDir.findFile(captionName)?.delete()
                        val caption = animeDir.createFile("application/x-subrip", captionName) ?: error("Cannot create caption file")
                        applicationContext.contentResolver.openOutputStream(caption.uri, "w")!!.use { it.write(match.second) }
                        subtitleUri = caption.uri.toString()
                    }
                }
            }
            preferences.addDownload(DownloadRecord(
                id = id.toString(), animeTitle = animeTitle, episodeLabel = episodeLabel,
                language = language, quality = quality, uri = output.uri.toString(),
                bytes = written, completedAt = System.currentTimeMillis(),
                animeId = inputData.getString("animeId").orEmpty(),
                animeImageUrl = inputData.getString("animeImageUrl").orEmpty(),
                animeUrl = inputData.getString("animeUrl").orEmpty(),
                sourceId = inputData.getString("sourceId") ?: "anidb",
                episodeNumber = inputData.getInt("episodeNumber", 0),
                episodeId = inputData.getString("episodeId").orEmpty(),
                seasonNumber = inputData.getInt("seasonNumber", 0),
                subtitleUri = subtitleUri
            ))
            partialUri = null
            Result.success(workDataOf("uri" to output.uri.toString()))
        }.getOrElse { error ->
            partialUri?.let { runCatching { applicationContext.contentResolver.delete(it, null, null) } }
            Result.failure(workDataOf("error" to (error.message ?: "Download failed")))
        }
    }

    private fun foregroundInfo(anime: String, episode: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Kairo downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(anime)
            .setContentText("$episode • $progress%")
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, progress, progress == 0).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode(), notification)
    }

    private fun progressData(
        anime: String,
        episode: String,
        progress: Int,
        bytes: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
        totalIsEstimate: Boolean
    ) = workDataOf(
        "progress" to progress,
        "title" to "$anime • $episode",
        "animeTitle" to anime,
        "episodeLabel" to episode,
        "bytes" to bytes,
        "totalBytes" to totalBytes,
        "bytesPerSecond" to bytesPerSecond,
        "totalIsEstimate" to totalIsEstimate
    )

    private suspend fun downloadDirect(
        url: String,
        referer: String,
        authToken: String,
        destination: OutputStream,
        anime: String,
        episode: String,
        estimate: Long
    ): Long {
        val connection = connection(url, referer, authToken)
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: estimate
        var written = 0L
        val startedAt = SystemClock.elapsedRealtime()
        var lastUpdate = 0L
        connection.inputStream.use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (isStopped) error("Download cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                destination.write(buffer, 0, count)
                written += count
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 500) {
                    val speed = written * 1_000L / (now - startedAt).coerceAtLeast(1)
                    val percent = if (total > 0) ((written * 100L) / total).toInt().coerceIn(0, 99) else 0
                    setProgress(progressData(anime, episode, percent, written, total, speed, false))
                    setForeground(foregroundInfo(anime, episode, percent))
                    lastUpdate = now
                }
            }
        }
        val speed = written * 1_000L / (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
        setProgress(progressData(anime, episode, 100, written, written, speed, false))
        return written
    }

    private suspend fun downloadHls(
        playlistUrl: String,
        sourceBase: String,
        destination: OutputStream,
        anime: String,
        episode: String,
        estimate: Long
    ): Long {
        var mediaUrl = playlistUrl
        var playlist = getText(mediaUrl, sourceBase)
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            mediaUrl = parseVariants(playlist, mediaUrl).lastOrNull() ?: error("No HLS variant found")
            playlist = getText(mediaUrl, sourceBase)
        }
        val parsed = parseMediaPlaylist(playlist, mediaUrl)
        if (parsed.segments.isEmpty()) error("The stream contains no video segments")
        var written = 0L
        val startedAt = SystemClock.elapsedRealtime()
        parsed.initSegment?.let { initUrl -> open(initUrl, sourceBase).use { written += it.copyTo(destination) } }
        parsed.segments.forEachIndexed { index, segment ->
            if (isStopped) error("Download cancelled")
            val bytes = open(segment.url, sourceBase).use(InputStream::readBytes)
            val decoded = segment.key?.let { decrypt(bytes, it, parsed.mediaSequence + index, sourceBase) } ?: bytes
            ByteArrayInputStream(decoded).use { written += it.copyTo(destination) }
            val percent = ((index + 1) * 100 / parsed.segments.size).coerceIn(0, 100)
            val speed = written * 1_000L / (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
            setProgress(progressData(anime, episode, percent, written, estimate, speed, true))
            setForeground(foregroundInfo(anime, episode, percent))
        }
        return written
    }

    private data class Encryption(val keyUrl: String, val iv: ByteArray?)
    private data class Segment(val url: String, val key: Encryption?)
    private data class MediaPlaylist(val segments: List<Segment>, val initSegment: String?, val mediaSequence: Int)

    private fun parseVariants(text: String, base: String): List<String> {
        val lines = text.lineSequence().map(String::trim).toList()
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) null
            else lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let { KairoRepository.resolveUrl(it, base) }
        }
    }

    private fun parseMediaPlaylist(text: String, base: String): MediaPlaylist {
        var activeKey: Encryption? = null
        var init: String? = null
        var mediaSequence = 0
        val segments = mutableListOf<Segment>()
        text.lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> mediaSequence = line.substringAfter(':').toIntOrNull() ?: 0
                line.startsWith("#EXT-X-MAP:") -> Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let { init = KairoRepository.resolveUrl(it, base) }
                line.startsWith("#EXT-X-KEY:") -> {
                    if (line.contains("METHOD=NONE")) activeKey = null
                    else {
                        val keyUri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                        val iv = Regex("IV=0x([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.let(::hexToBytes)
                        if (keyUri != null) activeKey = Encryption(KairoRepository.resolveUrl(keyUri, base), iv)
                    }
                }
                line.isNotBlank() && !line.startsWith("#") -> segments += Segment(KairoRepository.resolveUrl(line, base), activeKey)
            }
        }
        return MediaPlaylist(segments, init, mediaSequence)
    }

    private fun decrypt(bytes: ByteArray, encryption: Encryption, sequence: Int, referer: String): ByteArray {
        val key = open(encryption.keyUrl, referer).use(InputStream::readBytes)
        val iv = encryption.iv ?: ByteArray(16).also { buffer ->
            var value = sequence.toLong()
            for (index in 15 downTo 0) { buffer[index] = (value and 0xff).toByte(); value = value shr 8 }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(bytes)
    }

    private fun hexToBytes(value: String): ByteArray {
        val padded = value.padStart(32, '0').takeLast(32)
        return ByteArray(padded.length / 2) { index -> padded.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun getText(url: String, referer: String): String = open(url, referer).bufferedReader().use { it.readText() }

    private fun open(url: String, referer: String): InputStream {
        return connection(url, referer, "").inputStream
    }

    private fun connection(url: String, referer: String, authToken: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", KairoRepository.USER_AGENT)
            setRequestProperty("Referer", referer)
            if (authToken.isNotBlank()) setRequestProperty("X-Emby-Token", authToken)
        }

    private fun videoMime(container: String): String = when (container) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        else -> "video/mp4"
    }

    private fun sanitize(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(100)

    companion object { private const val CHANNEL = "kairo_downloads" }
}
