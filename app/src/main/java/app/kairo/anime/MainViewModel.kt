package app.kairo.anime

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.kairo.anime.data.*
import app.kairo.anime.data.captions.OpenSubtitlesClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val loading: Boolean = true,
    val query: String = "",
    val anime: List<Anime> = emptyList(),
    val error: String? = null
)

data class DownloadChoiceState(
    val anime: Anime,
    val episode: Episode,
    val loading: Boolean = true,
    val languages: List<LanguageOption> = emptyList(),
    val selectedLanguage: LanguageOption? = null,
    val qualities: List<QualityOption> = emptyList(),
    val selectedQuality: QualityOption? = null,
    val existingDownloads: List<DownloadRecord> = emptyList(),
    val error: String? = null
)

data class PlayRequest(
    val uri: String,
    val title: String,
    val meta: String,
    val referer: String = "",
    val authToken: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as KairoApp).repository

    var home by mutableStateOf(HomeState())
        private set
    var details by mutableStateOf<AnimeDetails?>(null)
        private set
    var detailsLoading by mutableStateOf(false)
        private set
    var downloadChoice by mutableStateOf<DownloadChoiceState?>(null)
        private set
    var activeDownloads by mutableStateOf<List<WorkInfo>>(emptyList())
        private set
    var completedDownloads by mutableStateOf(repository.preferences.downloads())
        private set
    var playbackProgress by mutableStateOf(repository.preferences.playbackProgress())
        private set
    var watchlist by mutableStateOf(repository.preferences.watchlist())
        private set
    var sources by mutableStateOf(repository.preferences.sources())
        private set
    var sourceValidation by mutableStateOf<String?>(null)
        private set
    var playRequest by mutableStateOf<PlayRequest?>(null)
        private set
    var instantPlaybackEnabled by mutableStateOf(repository.preferences.instantPlaybackEnabled)
        private set
    var openSubtitlesConnected by mutableStateOf(repository.preferences.openSubtitlesConnected)
        private set
    var autoDownloadCaptions by mutableStateOf(repository.preferences.autoDownloadCaptions)
        private set
    var subtitleLanguage by mutableStateOf(repository.preferences.subtitleLanguage)
        private set
    var captionValidation by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null

    init {
        refreshHome()
        viewModelScope.launch {
            while (true) {
                activeDownloads = withContext(Dispatchers.IO) {
                    runCatching {
                        WorkManager.getInstance(application).getWorkInfosByTag("kairo_downloads").get()
                            .filter { !it.state.isFinished }
                    }.getOrDefault(emptyList())
                }
                completedDownloads = repository.preferences.downloads()
                playbackProgress = repository.preferences.playbackProgress()
                delay(800)
            }
        }
    }

    fun refreshHome() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            home = home.copy(loading = true, error = null)
            home = runCatching { repository.browse() }
                .fold({ home.copy(loading = false, anime = it, error = null) }, { home.copy(loading = false, error = it.message ?: "Could not load anime") })
        }
    }

    fun search(value: String) {
        home = home.copy(query = value)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(if (value.isBlank()) 0 else 350)
            home = home.copy(loading = true, error = null)
            home = runCatching { repository.search(value) }
                .fold({ home.copy(loading = false, anime = it) }, { home.copy(loading = false, error = it.message ?: "Search failed") })
        }
    }

    fun openDetails(anime: Anime) {
        details = AnimeDetails(anime)
        detailsLoading = true
        viewModelScope.launch {
            runCatching { repository.details(anime) }
                .onSuccess { details = it }
            detailsLoading = false
        }
    }

    fun closeDetails() { details = null }

    fun chooseEpisode(anime: Anime, episode: Episode) {
        val existing = completedDownloads.filter { recordMatchesEpisode(it, anime, episode) }
        downloadChoice = DownloadChoiceState(anime, episode, existingDownloads = existing)
        viewModelScope.launch {
            runCatching { repository.languages(episode, repository.sourceFor(anime.sourceId)) }
                .onSuccess { languages ->
                    val preferred = languages.firstOrNull { it.label.equals(repository.preferences.defaultLanguage, true) } ?: languages.firstOrNull()
                    downloadChoice = downloadChoice?.copy(loading = preferred != null, languages = languages, selectedLanguage = preferred)
                    if (preferred != null) loadQualities(preferred)
                    else downloadChoice = downloadChoice?.copy(loading = false, error = "No languages are available for this episode")
                }
                .onFailure { downloadChoice = downloadChoice?.copy(loading = false, error = it.message ?: "Could not load languages") }
        }
    }

    fun selectLanguage(language: LanguageOption) {
        downloadChoice = downloadChoice?.copy(selectedLanguage = language, qualities = emptyList(), selectedQuality = null, loading = true, error = null)
        viewModelScope.launch { loadQualities(language) }
    }

    private suspend fun loadQualities(language: LanguageOption) {
        val choice = downloadChoice ?: return
        val source = repository.preferences.sources().firstOrNull { it.id == choice.anime.sourceId } ?: repository.selectedSource()
        runCatching { repository.qualities(language, source) }
            .onSuccess { qualities -> downloadChoice = downloadChoice?.copy(loading = false, qualities = qualities, selectedQuality = qualities.lastOrNull()) }
            .onFailure { downloadChoice = downloadChoice?.copy(loading = false, error = it.message ?: "Could not resolve video qualities") }
    }

    fun selectQuality(quality: QualityOption) { downloadChoice = downloadChoice?.copy(selectedQuality = quality) }

    fun startDownload(): Boolean {
        val choice = downloadChoice ?: return false
        val language = choice.selectedLanguage ?: return false
        val quality = choice.selectedQuality ?: return false
        if (quality.delivery == DeliveryKind.LOCAL) {
            downloadChoice = choice.copy(error = "This file is already stored on your device")
            return false
        }
        if (repository.preferences.downloadTreeUri == null) {
            downloadChoice = choice.copy(error = "Choose a download folder in Settings first")
            return false
        }
        repository.enqueueDownload(choice.anime, choice.episode, language, quality)
        downloadChoice = null
        return true
    }

    fun playSelected(): Boolean {
        val choice = downloadChoice ?: return false
        val quality = choice.selectedQuality ?: return false
        if (quality.delivery != DeliveryKind.LOCAL && !instantPlaybackEnabled) {
            downloadChoice = choice.copy(error = "Instant playback is off. Download the episode first or enable it in Settings.")
            return false
        }
        val source = repository.sourceFor(choice.anime.sourceId)
        playRequest = PlayRequest(
            uri = quality.url,
            title = "${choice.anime.title} • ${choice.episode.label}",
            meta = "${choice.selectedLanguage?.label.orEmpty()} • ${quality.label}",
            referer = if (quality.delivery == DeliveryKind.HLS) source.baseUrl else "",
            authToken = if (source.kind == SourceKind.JELLYFIN) source.authToken else ""
        )
        downloadChoice = null
        return true
    }

    fun consumePlayRequest() { playRequest = null }

    fun dismissChoice() { downloadChoice = null }

    fun refreshDownloads() { completedDownloads = repository.preferences.downloads() }

    fun cancelDownload(id: java.util.UUID) {
        WorkManager.getInstance(getApplication()).cancelWorkById(id)
    }

    fun deleteDownload(record: DownloadRecord, deleteFile: Boolean) {
        repository.removeDownload(record, deleteFile)
        refreshDownloads()
    }

    fun toggleWatchlist(anime: Anime) {
        repository.preferences.toggleWatchlist(anime)
        watchlist = repository.preferences.watchlist()
    }

    fun isWatchlisted(anime: Anime): Boolean = watchlist.any {
        it.anime.id == anime.id && it.anime.sourceId == anime.sourceId
    }

    fun downloadsFor(anime: Anime, episode: Episode): List<DownloadRecord> =
        completedDownloads.filter { recordMatchesEpisode(it, anime, episode) }

    private fun recordMatchesEpisode(record: DownloadRecord, anime: Anime, episode: Episode): Boolean {
        val animeMatches = if (record.animeId.isNotBlank()) {
            record.animeId == anime.id && record.sourceId == anime.sourceId
        } else record.animeTitle.equals(anime.title, true)
        val episodeMatches = if (record.episodeId.isNotBlank()) record.episodeId == episode.id
        else if (record.episodeNumber > 0) record.episodeNumber == episode.number && record.seasonNumber == episode.seasonNumber
        else record.episodeLabel.equals(episode.label, true)
        return animeMatches && episodeMatches
    }

    fun selectSource(source: SourceDefinition) {
        repository.preferences.selectedSourceId = source.id
        refreshHome()
    }

    fun findPlayable(anime: Anime) {
        val source = sources.firstOrNull { it.enabled && it.kind == SourceKind.KAIRO_COMPATIBLE }
            ?: sources.firstOrNull { it.enabled && it.kind == SourceKind.JELLYFIN }
            ?: return
        repository.preferences.selectedSourceId = source.id
        details = null
        home = home.copy(query = anime.title)
        search(anime.title)
    }

    fun addSource(name: String, url: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            sourceValidation = "Checking source…"
            val valid = repository.validateSource(url)
            if (valid) {
                repository.preferences.addSource(name, url)
                sources = repository.preferences.sources()
                sourceValidation = null
            } else sourceValidation = "This URL does not expose a compatible anime catalog"
            done(valid)
        }
    }

    fun addJellyfinSource(name: String, url: String, token: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            sourceValidation = "Connecting securely to Jellyfin…"
            runCatching { repository.validateJellyfin(url, token) }
                .onSuccess { connection ->
                    repository.preferences.addJellyfinSource(name.ifBlank { connection.serverName }, url, token, connection.userId)
                    sources = repository.preferences.sources()
                    sourceValidation = null
                    done(true)
                }
                .onFailure { error ->
                    sourceValidation = error.message ?: "Could not connect to this Jellyfin server"
                    done(false)
                }
        }
    }

    fun localFolderChanged(uri: String) {
        repository.preferences.localMediaTreeUri = uri
        sources = repository.preferences.sources()
        if (repository.preferences.selectedSourceId == "local") refreshHome()
    }

    fun setInstantPlayback(enabled: Boolean) {
        repository.preferences.instantPlaybackEnabled = enabled
        instantPlaybackEnabled = enabled
    }

    fun connectOpenSubtitles(
        apiKey: String,
        username: String,
        password: String,
        language: String,
        done: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            captionValidation = "Connecting to OpenSubtitles…"
            runCatching { OpenSubtitlesClient(getApplication()).login(apiKey, username, password) }
                .onSuccess { session ->
                    repository.preferences.openSubtitlesApiKey = apiKey.trim()
                    repository.preferences.openSubtitlesToken = session.token
                    repository.preferences.openSubtitlesBaseUrl = session.baseUrl
                    repository.preferences.subtitleLanguage = language
                    openSubtitlesConnected = true
                    subtitleLanguage = language
                    captionValidation = null
                    done(true)
                }
                .onFailure { error ->
                    captionValidation = error.message ?: "Could not connect to OpenSubtitles"
                    done(false)
                }
        }
    }

    fun disconnectOpenSubtitles() {
        repository.preferences.disconnectOpenSubtitles()
        openSubtitlesConnected = false
        autoDownloadCaptions = false
        captionValidation = null
    }

    fun updateAutoDownloadCaptions(enabled: Boolean) {
        if (!openSubtitlesConnected) return
        repository.preferences.autoDownloadCaptions = enabled
        autoDownloadCaptions = enabled
    }

    fun removeSource(id: String) { repository.preferences.removeSource(id); sources = repository.preferences.sources(); refreshHome() }
    fun toggleSource(id: String) { repository.preferences.toggleSource(id); sources = repository.preferences.sources() }
}
