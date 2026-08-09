package app.kairo.anime.data

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.kairo.anime.data.source.*
import app.kairo.anime.download.AnimeDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.UUID

class KairoRepository(private val context: Context) {
    val preferences = KairoPreferences(context)

    private val compatibleAdapter = KairoCompatibleAdapter()
    private val jellyfinAdapter = JellyfinAdapter()
    private val stremioAdapter = StremioAddonAdapter(preferences)
    private val adapters: Map<SourceKind, AnimeSourceAdapter> = listOf(
        compatibleAdapter,
        stremioAdapter,
        AniListAdapter(),
        jellyfinAdapter,
        LocalFolderAdapter(context, preferences)
    ).associateBy(AnimeSourceAdapter::kind)

    fun selectedSource(): SourceDefinition {
        val sources = preferences.sources()
        return sources.firstOrNull { it.id == preferences.selectedSourceId && it.enabled }
            ?: sources.first { it.enabled }
    }

    suspend fun browse(source: SourceDefinition = selectedSource()): List<Anime> = withContext(Dispatchers.IO) {
        adapter(source).browse(source)
    }

    suspend fun search(query: String, source: SourceDefinition = selectedSource()): List<Anime> = withContext(Dispatchers.IO) {
        adapter(source).search(query, source)
    }

    suspend fun details(anime: Anime): AnimeDetails = withContext(Dispatchers.IO) {
        val source = sourceFor(anime.sourceId)
        adapter(source).details(anime, source)
    }

    suspend fun languages(episode: Episode, source: SourceDefinition = selectedSource()): List<LanguageOption> = withContext(Dispatchers.IO) {
        adapter(source).languages(episode, source)
    }

    suspend fun qualities(language: LanguageOption, source: SourceDefinition = selectedSource()): List<QualityOption> = withContext(Dispatchers.IO) {
        adapter(source).qualities(language, source)
    }

    suspend fun validateSource(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        compatibleAdapter.validate(baseUrl)
    }

    suspend fun validateJellyfin(baseUrl: String, token: String): JellyfinConnection = withContext(Dispatchers.IO) {
        jellyfinAdapter.validate(baseUrl, token)
    }

    suspend fun validateStremioAddon(url: String): StremioConnection = withContext(Dispatchers.IO) {
        stremioAdapter.validate(url)
    }

    fun sourceFor(id: String): SourceDefinition = preferences.sources().firstOrNull { it.id == id } ?: selectedSource()

    fun enqueueDownload(anime: Anime, episode: Episode, language: LanguageOption, quality: QualityOption): UUID {
        check(quality.delivery != DeliveryKind.LOCAL) { "Local files are already stored on this device" }
        val source = sourceFor(anime.sourceId)
        val input = Data.Builder()
            .putString("animeTitle", anime.title)
            .putString("episodeLabel", episode.label)
            .putString("language", language.label)
            .putString("quality", quality.label)
            .putString("playlistUrl", quality.url)
            .putString("sourceBase", source.baseUrl)
            .putLong("estimatedBytes", quality.estimatedBytes)
            .putString("delivery", quality.delivery.name)
            .putString("container", quality.container)
            .putString("authToken", source.authToken)
            .putString("animeId", anime.id)
            .putString("animeImageUrl", anime.imageUrl)
            .putString("animeUrl", anime.url)
            .putString("sourceId", anime.sourceId)
            .putInt("episodeNumber", episode.number)
            .putString("episodeId", episode.id)
            .putInt("seasonNumber", episode.seasonNumber)
            .build()
        val request = OneTimeWorkRequestBuilder<AnimeDownloadWorker>().setInputData(input).addTag("kairo_downloads").build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }

    fun imageHeaders(url: String): Map<String, String> {
        val sourceId = Uri.parse(url).fragment?.substringAfter("kairo-source=", "").orEmpty()
        val source = preferences.sources().firstOrNull { it.id == sourceId && it.kind == SourceKind.JELLYFIN }
        return source?.authToken?.takeIf(String::isNotBlank)?.let { mapOf("X-Emby-Token" to it) }.orEmpty()
    }

    fun removeDownload(record: DownloadRecord, deleteFile: Boolean) {
        if (deleteFile) {
            runCatching { context.contentResolver.delete(Uri.parse(record.uri), null, null) }
            record.subtitleUri.takeIf(String::isNotBlank)?.let { uri ->
                runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            }
        }
        preferences.removeDownload(record.id)
    }

    private fun adapter(source: SourceDefinition): AnimeSourceAdapter =
        adapters[source.kind] ?: error("Unsupported source type: ${source.kind}")

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 Kairo/2.1.1"

        fun resolveUrl(value: String, base: String): String {
            if (value.startsWith("http://") || value.startsWith("https://")) return value
            return URL(URL(base), value).toString()
        }
    }
}
