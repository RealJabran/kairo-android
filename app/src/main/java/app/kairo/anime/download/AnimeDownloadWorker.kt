package app.kairo.anime.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kairo.anime.R
import app.kairo.anime.data.DownloadRecord
import app.kairo.anime.data.DeliveryKind
import app.kairo.anime.data.KairoPreferences
import app.kairo.anime.data.KairoRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
class AnimeDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val preferences = KairoPreferences(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val animeTitle = inputData.getString("animeTitle") ?: return@withContext Result.failure()
        val episodeLabel = inputData.getString("episodeLabel") ?: return@withContext Result.failure()
        val language = inputData.getString("language").orEmpty()
        val quality = inputData.getString("quality").orEmpty()
        val mediaUrl = inputData.getString("playlistUrl") ?: return@withContext Result.failure()
        val sourceBase = inputData.getString("sourceBase") ?: mediaUrl
        val estimatedBytes = inputData.getLong("estimatedBytes", 0)
        val delivery = runCatching {
            DeliveryKind.valueOf(inputData.getString("delivery") ?: DeliveryKind.HLS.name)
        }.getOrDefault(DeliveryKind.HLS)
        val requestedContainer = sanitize(inputData.getString("container").orEmpty().ifBlank { "mp4" }).lowercase()
        val container = if (delivery == DeliveryKind.HLS) "mp4" else requestedContainer
        val authToken = inputData.getString("authToken").orEmpty()
        val hlsAudioUrl = inputData.getString("hlsAudioUrl").orEmpty()
        val tree = preferences.downloadTreeUri?.let(Uri::parse)
            ?: return@withContext Result.failure(workDataOf("error" to "Choose a download folder in Settings"))
        val previousVersions = preferences.downloads().filter {
            it.animeTitle.equals(animeTitle, true) && it.episodeLabel.equals(episodeLabel, true) &&
                it.language.equals(language, true) && it.quality.equals(quality, true)
        }

        setForeground(foregroundInfo(animeTitle, episodeLabel, 0))
        setProgress(progressData(animeTitle, episodeLabel, 0, 0, estimatedBytes, 0, delivery == DeliveryKind.HLS))

        var partialUri: Uri? = null
        var stagedFile: File? = null
        runCatching {
            val root = DocumentFile.fromTreeUri(applicationContext, tree)
                ?: error("Download folder is unavailable")
            val animeDir = root.findFile(sanitize(animeTitle))
                ?: root.createDirectory(sanitize(animeTitle))
                ?: error("Cannot create anime folder")

            if (delivery == DeliveryKind.LOCAL) error("Local files do not need to be downloaded")

            val outputName = buildString {
                append(sanitize(episodeLabel)).append(" • ").append(sanitize(language)).append(" • ")
                append(sanitize(quality)).append(" • ").append(id.toString().take(8)).append('.').append(container)
            }

            val written: Long
            val output: DocumentFile
            if (delivery == DeliveryKind.HLS) {
                stagedFile = exportHls(
                    mediaUrl = mediaUrl,
                    externalAudioUrl = hlsAudioUrl,
                    referer = sourceBase,
                    authToken = authToken,
                    anime = animeTitle,
                    episode = episodeLabel,
                    estimate = estimatedBytes
                )
                output = animeDir.createFile(videoMime(container), outputName)
                    ?: error("Cannot create output file")
                partialUri = output.uri
                written = applicationContext.contentResolver.openOutputStream(output.uri, "w")!!.use { destination ->
                    stagedFile!!.inputStream().use { input -> input.copyTo(destination, 128 * 1024) }
                }
            } else {
                output = animeDir.createFile(videoMime(container), outputName)
                    ?: error("Cannot create output file")
                partialUri = output.uri
                written = applicationContext.contentResolver.openOutputStream(output.uri, "w")!!.use { destination ->
                    downloadDirect(mediaUrl, sourceBase, authToken, destination, animeTitle, episodeLabel, estimatedBytes)
                }
            }

            val record = DownloadRecord(
                id = id.toString(), animeTitle = animeTitle, episodeLabel = episodeLabel,
                language = language, quality = quality, uri = output.uri.toString(),
                bytes = written, completedAt = System.currentTimeMillis(),
                animeId = inputData.getString("animeId").orEmpty(),
                animeImageUrl = inputData.getString("animeImageUrl").orEmpty(),
                animeUrl = inputData.getString("animeUrl").orEmpty(),
                sourceId = inputData.getString("sourceId") ?: "anidb",
                episodeNumber = inputData.getInt("episodeNumber", 0),
                episodeId = inputData.getString("episodeId").orEmpty(),
                seasonNumber = inputData.getInt("seasonNumber", 0)
            )
            check(preferences.addDownload(record)) { "Could not save the completed download" }

            // The completed replacement is durable before an older version is removed. If the
            // process dies during cleanup the worst outcome is an orphaned old file, never data loss.
            previousVersions.filterNot { it.uri == record.uri }.forEach { previous ->
                runCatching { applicationContext.contentResolver.delete(Uri.parse(previous.uri), null, null) }
                previous.subtitleUri.takeIf(String::isNotBlank)?.let { subtitle ->
                    runCatching { applicationContext.contentResolver.delete(Uri.parse(subtitle), null, null) }
                }
            }

            partialUri = null
            setProgress(progressData(animeTitle, episodeLabel, 100, written, written, 0, false))
            Result.success(workDataOf("uri" to output.uri.toString()))
        }.getOrElse { error ->
            partialUri?.let { runCatching { applicationContext.contentResolver.delete(it, null, null) } }
            Result.failure(workDataOf("error" to (error.message ?: "Download failed")))
        }.also {
            stagedFile?.delete()
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
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id.hashCode(), notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(id.hashCode(), notification)
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

    private suspend fun exportHls(
        mediaUrl: String,
        externalAudioUrl: String,
        referer: String,
        authToken: String,
        anime: String,
        episode: String,
        estimate: Long
    ): File {
        val cache = applicationContext.externalCacheDir ?: applicationContext.cacheDir
        val output = File(cache, "kairo-export-${id}.mp4").also { it.delete() }
        val syntheticManifest = externalAudioUrl.takeIf(String::isNotBlank)?.let { audioUrl ->
            File(cache, "kairo-master-${id}.m3u8").apply {
                writeText(
                    """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="kairo-audio",NAME="Selected audio",DEFAULT=YES,AUTOSELECT=YES,URI="${hlsValue(audioUrl)}"
                    #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="kairo-audio"
                    ${hlsValue(mediaUrl)}
                    """.trimIndent()
                )
            }
        }
        val inputUri = syntheticManifest?.let(Uri::fromFile) ?: Uri.parse(mediaUrl)
        val completion = CompletableDeferred<Throwable?>()
        lateinit var transformer: Transformer

        withContext(Dispatchers.Main.immediate) {
            val headers = buildMap {
                if (referer.isNotBlank()) put("Referer", referer)
                if (authToken.isNotBlank()) put("X-Emby-Token", authToken)
            }
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(KairoRepository.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
            if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)
            val dataSourceFactory = DefaultDataSource.Factory(applicationContext, httpFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            val assetLoaderFactory = ExoPlayerAssetLoader.Factory(
                applicationContext,
                DefaultDecoderFactory(applicationContext),
                Clock.DEFAULT,
                mediaSourceFactory
            )
            transformer = Transformer.Builder(applicationContext)
                .setLooper(Looper.getMainLooper())
                .setAssetLoaderFactory(assetLoaderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        completion.complete(null)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        completion.complete(exportException)
                    }
                })
                .build()
            transformer.start(
                MediaItem.Builder().setUri(inputUri).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
                output.absolutePath
            )
        }

        try {
            val holder = ProgressHolder()
            while (!completion.isCompleted) {
                if (isStopped) {
                    withContext(Dispatchers.Main.immediate) { transformer.cancel() }
                    error("Download cancelled")
                }
                val percent = withContext(Dispatchers.Main.immediate) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress.coerceIn(0, 99)
                    else 0
                }
                val currentBytes = output.length()
                setProgress(progressData(anime, episode, percent, currentBytes, estimate, 0, true))
                setForeground(foregroundInfo(anime, episode, percent))
                delay(500)
            }
            completion.await()?.let { throw it }
            require(output.isFile && output.length() > 0) { "The HLS export produced no playable file" }
            return output
        } catch (error: Throwable) {
            withContext(Dispatchers.Main.immediate) { transformer.cancel() }
            output.delete()
            throw error
        } finally {
            syntheticManifest?.delete()
        }
    }

    private fun connection(url: String, referer: String, authToken: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
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

    private fun hlsValue(value: String): String = value.replace("\"", "%22").replace("\r", "").replace("\n", "")

    private fun sanitize(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ").trim().take(100)

    companion object {
        private const val CHANNEL = "kairo_downloads"
    }
}
