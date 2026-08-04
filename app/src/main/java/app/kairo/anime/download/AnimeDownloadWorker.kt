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
import app.kairo.anime.data.KairoPreferences
import app.kairo.anime.data.KairoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
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
        val tree = preferences.downloadTreeUri?.let(Uri::parse) ?: return@withContext Result.failure(workDataOf("error" to "Choose a download folder in Settings"))

        setForeground(foregroundInfo(animeTitle, episodeLabel, 0))
        setProgress(progressData(animeTitle, episodeLabel, 0, 0, estimatedBytes, 0))

        var partialUri: Uri? = null
        runCatching {
            val root = DocumentFile.fromTreeUri(applicationContext, tree) ?: error("Download folder is unavailable")
            val animeDir = root.findFile(sanitize(animeTitle)) ?: root.createDirectory(sanitize(animeTitle)) ?: error("Cannot create anime folder")
            val outputName = "${sanitize(episodeLabel)} • ${sanitize(language)} • ${sanitize(quality)}.mp4"
            animeDir.findFile(outputName)?.delete()
            val output = animeDir.createFile("video/mp4", outputName) ?: error("Cannot create output file")
            partialUri = output.uri

            var mediaUrl = playlistUrl
            var playlist = getText(mediaUrl, sourceBase)
            if (playlist.contains("#EXT-X-STREAM-INF")) {
                val variants = parseVariants(playlist, mediaUrl)
                mediaUrl = variants.lastOrNull() ?: error("No HLS variant found")
                playlist = getText(mediaUrl, sourceBase)
            }
            val parsed = parseMediaPlaylist(playlist, mediaUrl)
            if (parsed.segments.isEmpty()) error("The stream contains no video segments")

            var written = 0L
            val startedAt = SystemClock.elapsedRealtime()
            applicationContext.contentResolver.openOutputStream(output.uri, "w")!!.use { destination ->
                parsed.initSegment?.let { initUrl ->
                    open(initUrl, sourceBase).use { it.copyTo(destination).also { count -> written += count } }
                }
                parsed.segments.forEachIndexed { index, segment ->
                    if (isStopped) error("Download cancelled")
                    val bytes = open(segment.url, sourceBase).use(InputStream::readBytes)
                    val decoded = segment.key?.let { decrypt(bytes, it, parsed.mediaSequence + index, sourceBase) } ?: bytes
                    ByteArrayInputStream(decoded).use { input -> written += input.copyTo(destination) }
                    val percent = ((index + 1) * 100 / parsed.segments.size).coerceIn(0, 100)
                    val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
                    val speed = written * 1_000L / elapsedMs
                    setProgress(progressData(animeTitle, episodeLabel, percent, written, estimatedBytes, speed))
                    setForeground(foregroundInfo(animeTitle, episodeLabel, percent))
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
                episodeNumber = inputData.getInt("episodeNumber", 0)
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
        bytesPerSecond: Long
    ) = workDataOf(
        "progress" to progress,
        "title" to "$anime • $episode",
        "animeTitle" to anime,
        "episodeLabel" to episode,
        "bytes" to bytes,
        "totalBytes" to totalBytes,
        "bytesPerSecond" to bytesPerSecond
    )

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
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 30_000; connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", KairoRepository.USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        return connection.inputStream
    }

    private fun sanitize(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(100)

    companion object { private const val CHANNEL = "kairo_downloads" }
}
