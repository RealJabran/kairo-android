package app.kairo.anime.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.kairo.anime.data.KairoPreferences
import app.kairo.anime.KairoApp
import app.kairo.anime.data.Anime
import app.kairo.anime.data.Episode
import app.kairo.anime.data.PlaybackProgress
import app.kairo.anime.data.PlaybackEpisode
import app.kairo.anime.data.PlaybackNavigation
import app.kairo.anime.data.captions.OnlineSubtitle
import app.kairo.anime.data.captions.OpenSubtitlesClient
import app.kairo.anime.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var preferences: KairoPreferences
    private var recordId = ""
    private var inPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        val uri = intent.data
        if (uri == null) { finish(); return }
        preferences = KairoPreferences(this)
        recordId = intent.getStringExtra(EXTRA_RECORD_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val meta = intent.getStringExtra(EXTRA_META).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()
        val subtitleUri = intent.getStringExtra(EXTRA_SUBTITLE_URI).orEmpty()
        val qualityLabels = intent.getStringArrayListExtra(EXTRA_QUALITY_LABELS).orEmpty()
        val qualityUrls = intent.getStringArrayListExtra(EXTRA_QUALITY_URLS).orEmpty()
        val selectedQuality = intent.getStringExtra(EXTRA_SELECTED_QUALITY).orEmpty()
        val navigation = intent.getStringExtra(EXTRA_NAVIGATION)?.let(::decodePlaybackNavigation)
        val qualityOptions = qualityLabels.zip(qualityUrls).map { PlayerQuality(it.first, it.second) }
            .distinctBy { it.label to it.url }
        val startPosition = preferences.playbackProgress().firstOrNull { it.recordId == recordId }
            ?.takeIf { it.percent in 1..94 }?.positionMs ?: 0
        setContent {
            KairoTheme {
                PlayerScreen(
                    uri = uri.toString(), title = title, meta = meta, startPositionMs = startPosition,
                    referer = referer, authToken = authToken, initialSubtitleUri = subtitleUri,
                    qualityOptions = qualityOptions, selectedQuality = selectedQuality,
                    navigation = navigation,
                    onBack = { finish() }, onPlayer = { player = it }, onProgress = ::persistProgress,
                    onRecordChanged = { recordId = it },
                    onPictureInPicture = ::enterPictureInPicture,
                    isPictureInPicture = inPictureInPicture,
                    onPlaybackActive = ::setScreenAwake
                )
            }
        }
        configurePictureInPicture()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun configurePictureInPicture() {
        if (Build.VERSION.SDK_INT >= 31) {
            window.decorView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                if (view.width > 0 && view.height > 0) {
                    setPictureInPictureParams(
                        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setAutoEnterEnabled(true)
                            .setSourceRectHint(Rect(0, 0, view.width, view.height)).build()
                    )
                }
            }
        }
    }

    private fun enterPictureInPicture() {
        if (player?.playbackState != Player.STATE_IDLE) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && player?.isPlaying == true) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onPause() {
        player?.let { persistProgress(it.currentPosition, it.duration) }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        setScreenAwake(false)
        player?.let { persistProgress(it.currentPosition, it.duration) }
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun persistProgress(positionMs: Long, durationMs: Long) {
        if (recordId.isBlank() || durationMs <= 0 || durationMs == C.TIME_UNSET) return
        preferences.savePlaybackProgress(
            PlaybackProgress(recordId, positionMs.coerceIn(0, durationMs), durationMs, System.currentTimeMillis())
        )
    }

    private fun setScreenAwake(awake: Boolean) {
        if (awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_META = "meta"
        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_SUBTITLE_URI = "subtitle_uri"
        const val EXTRA_QUALITY_LABELS = "quality_labels"
        const val EXTRA_QUALITY_URLS = "quality_urls"
        const val EXTRA_SELECTED_QUALITY = "selected_quality"
        const val EXTRA_NAVIGATION = "playback_navigation"
        const val EXTRA_ANIME_TITLE = "anime_title"
        const val EXTRA_EPISODE_LABEL = "episode_label"
    }
}

private data class PlayerQuality(val label: String, val url: String)
private data class PlayerMediaTrack(
    val group: TrackGroup,
    val index: Int,
    val type: Int,
    val label: String,
    val selected: Boolean
)

private enum class GestureKind { Brightness, Volume }

@androidx.annotation.OptIn(UnstableApi::class)
private enum class ScreenMode(val label: String, val resizeMode: Int) {
    Fit("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Crop("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    Stretch("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    Original("100%", AspectRatioFrameLayout.RESIZE_MODE_FIT)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(
    uri: String,
    title: String,
    meta: String,
    startPositionMs: Long,
    referer: String,
    authToken: String,
    initialSubtitleUri: String,
    qualityOptions: List<PlayerQuality>,
    selectedQuality: String,
    navigation: PlaybackNavigation?,
    onBack: () -> Unit,
    onPlayer: (ExoPlayer) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onRecordChanged: (String) -> Unit,
    onPictureInPicture: () -> Unit,
    isPictureInPicture: Boolean,
    onPlaybackActive: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { (context.applicationContext as KairoApp).repository }
    val onlineClient = remember { OpenSubtitlesClient(context) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var screenMode by remember { mutableStateOf(ScreenMode.Fit) }
    var captionMenu by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var audioTracks by remember { mutableStateOf<List<PlayerMediaTrack>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<PlayerMediaTrack>>(emptyList()) }
    var controlsLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var activeUri by remember(uri) { mutableStateOf(uri) }
    var availableQualities by remember(qualityOptions) { mutableStateOf(qualityOptions) }
    var activeQuality by remember(selectedQuality, qualityOptions) {
        mutableStateOf(selectedQuality.ifBlank { qualityOptions.firstOrNull()?.label ?: "Original" })
    }
    var activeTitle by remember(title) { mutableStateOf(title) }
    var activeMeta by remember(meta) { mutableStateOf(meta) }
    var activeNavigation by remember(navigation) { mutableStateOf(navigation) }
    var activeEpisodeIndex by remember(navigation) {
        mutableIntStateOf(navigation?.episodes?.indexOfFirst { it.id == navigation.currentEpisodeId }?.takeIf { it >= 0 } ?: 0)
    }
    var navigationLoading by remember { mutableStateOf(false) }
    var captionUri by remember(initialSubtitleUri) { mutableStateOf(initialSubtitleUri.takeIf(String::isNotBlank)?.let(Uri::parse)) }
    var captionName by remember(initialSubtitleUri) { mutableStateOf(initialSubtitleUri.takeIf(String::isNotBlank)?.let { "Downloaded captions" }) }
    var onlineDialog by remember { mutableStateOf(false) }
    var onlineLoading by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    var onlineResults by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    var seekFeedbackSequence by remember { mutableIntStateOf(0) }
    var gestureKind by remember { mutableStateOf<GestureKind?>(null) }
    var gestureValue by remember { mutableFloatStateOf(0f) }
    var gestureFeedbackSequence by remember { mutableIntStateOf(0) }
    var captionLanguage by remember { mutableStateOf("English") }

    val activeEpisode = activeNavigation?.episodes?.getOrNull(activeEpisodeIndex)
    val searchTitle = activeNavigation?.animeTitle ?: activeTitle.substringBefore(" • ").trim()
    val episodeText = activeTitle.substringAfter(" • ", "")
    val searchSeason = activeEpisode?.seasonNumber ?: Regex("S(\\d+)", RegexOption.IGNORE_CASE)
        .find(episodeText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val searchEpisode = activeEpisode?.number ?: Regex("(?:E|Episode\\s+)(\\d+)", RegexOption.IGNORE_CASE)
        .find(episodeText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun buildMediaItem(subtitle: Uri?, mediaUri: String = activeUri): MediaItem {
        val builder = MediaItem.Builder().setUri(mediaUri)
        if (subtitle != null) {
            val configuration = MediaItem.SubtitleConfiguration.Builder(subtitle)
                .setMimeType(subtitleMimeType(context, subtitle))
                .setLanguage(captionLanguageTag(captionLanguage))
                .setLabel(captionName ?: "External captions")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT)
                .build()
            builder.setSubtitleConfigurations(listOf(configuration))
        }
        return builder.build()
    }

    val player = remember(uri, referer, authToken) {
        val requestHeaders = buildMap {
            if (referer.isNotBlank()) put("Referer", referer)
            if (authToken.isNotBlank()) put("X-Emby-Token", authToken)
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(app.kairo.anime.data.KairoRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        if (requestHeaders.isNotEmpty()) httpFactory.setDefaultRequestProperties(requestHeaders)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(captionLanguageTag(captionLanguage))
                .setSelectUndeterminedTextLanguage(true)
                .build()
            setMediaItem(buildMediaItem(captionUri))
            if (startPositionMs > 0) seekTo(startPositionMs)
            playWhenReady = true
            prepare()
            addListener(object : Player.Listener {
                override fun onPlayerError(playerError: PlaybackException) {
                    error = playerError.localizedMessage ?: "This video could not be decoded"
                }
                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                    if (state == Player.STATE_READY) error = null
                    if (state == Player.STATE_ENDED) {
                        onProgress(duration, duration)
                        controlsVisible = true
                    }
                    onPlaybackActive(playWhenReady && state != Player.STATE_IDLE && state != Player.STATE_ENDED)
                }
                override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
                override fun onTracksChanged(tracks: Tracks) {
                    audioTracks = playerTracks(tracks, C.TRACK_TYPE_AUDIO)
                    subtitleTracks = playerTracks(tracks, C.TRACK_TYPE_TEXT)
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    onPlaybackActive(playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED)
                }
                override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            })
        }
    }

    fun replaceMediaItem(subtitle: Uri?) {
        val position = player.currentPosition
        val shouldPlay = player.playWhenReady
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(captionLanguageTag(captionLanguage))
            .setSelectUndeterminedTextLanguage(true)
            .build()
        player.setMediaItem(buildMediaItem(subtitle), position)
        player.prepare()
        player.playWhenReady = shouldPlay
        playerView?.subtitleView?.visibility = View.VISIBLE
    }

    fun switchQuality(option: PlayerQuality) {
        if (option.url == activeUri) {
            activeQuality = option.label
            return
        }
        val position = player.currentPosition
        val shouldPlay = player.playWhenReady
        activeUri = option.url
        activeQuality = option.label
        error = null
        player.setMediaItem(buildMediaItem(captionUri, option.url), position)
        player.prepare()
        player.playWhenReady = shouldPlay
    }

    fun selectTrack(track: PlayerMediaTrack) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(track.type, false)
            .setOverrideForType(TrackSelectionOverride(track.group, track.index))
            .build()
        controlsVisible = true
    }

    fun navigateEpisode(delta: Int) {
        val nav = activeNavigation ?: return
        if (navigationLoading) return
        val nextIndex = activeEpisodeIndex + delta
        val target = nav.episodes.getOrNull(nextIndex) ?: return
        navigationLoading = true
        error = null
        scope.launch {
            val result = runCatching {
                if (target.directUri.isNotBlank()) {
                    listOf(PlayerQuality(target.qualityLabel.ifBlank { "On device" }, target.directUri))
                } else {
                    val source = repository.sourceFor(nav.sourceId)
                    val episode = Episode(target.id, target.number, target.title, seasonNumber = target.seasonNumber)
                    val languages = repository.languages(episode, source)
                    val language = languages.firstOrNull {
                        it.code.equals(nav.languageCode, true) || it.label.equals(nav.languageName, true) ||
                            it.name.equals(nav.languageName, true)
                    } ?: languages.firstOrNull {
                        nav.languageName.contains("Hindi", true) && it.label.contains("Hindi", true)
                    } ?: languages.firstOrNull() ?: error("No audio language is available for ${episode.label}")
                    repository.qualities(language, source).map { PlayerQuality(it.label, it.url) }
                }
            }
            result.onSuccess { resolved ->
                if (resolved.isEmpty()) {
                    error = "No playable quality is available for this episode"
                } else {
                    onProgress(player.currentPosition, player.duration)
                    val selected = resolved.firstOrNull { it.label.equals(activeQuality, true) }
                        ?: resolved.firstOrNull { it.label.equals("Auto", true) }
                        ?: resolved.last()
                    val targetCaption = target.subtitleUri.takeIf(String::isNotBlank)?.let(Uri::parse)
                    activeEpisodeIndex = nextIndex
                    availableQualities = resolved
                    activeUri = selected.url
                    activeQuality = selected.label
                    activeTitle = "${nav.animeTitle} • ${episodeLabel(target)}"
                    activeMeta = "${nav.languageName.ifBlank { "Audio" }} • ${selected.label}"
                    captionUri = targetCaption
                    captionName = targetCaption?.let { "Downloaded captions" }
                    onlineResults = emptyList()
                    onlineError = null
                    onRecordChanged(target.recordId)
                    player.setMediaItem(buildMediaItem(targetCaption, selected.url), 0L)
                    player.prepare()
                    player.playWhenReady = true
                }
            }.onFailure { failure ->
                error = failure.message ?: "The episode could not be opened"
            }
            navigationLoading = false
        }
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, max(0, duration - 1)))
        seekFeedback = if (deltaMs < 0) "−10 seconds" else "+10 seconds"
        seekFeedbackSequence++
    }

    fun searchOnlineCaptions(language: String = captionLanguage) {
        captionLanguage = language
        onlineLoading = true
        onlineError = null
        onlineResults = emptyList()
        scope.launch {
            runCatching { onlineClient.search(searchTitle, searchSeason, searchEpisode, language) }
                .onSuccess { results ->
                    onlineResults = results
                    if (results.isEmpty()) onlineError = "No matching $language captions were found"
                }
                .onFailure { onlineError = it.message ?: "Caption search failed" }
            onlineLoading = false
        }
    }

    val captionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            captionName = displayName(context, selected)
            captionUri = selected
            replaceMediaItem(selected)
        }
    }

    DisposableEffect(player) {
        onPlayer(player)
        onDispose { onPlaybackActive(false) }
    }
    LaunchedEffect(navigation) {
        val nav = navigation ?: return@LaunchedEffect
        if (nav.complete || nav.animeTitle.isBlank()) return@LaunchedEffect
        navigationLoading = true
        runCatching {
            val source = repository.sourceFor(nav.sourceId)
            val anime = if (nav.animeUrl.isNotBlank()) {
                Anime(nav.animeId, nav.animeTitle, "", nav.animeUrl, sourceId = nav.sourceId)
            } else {
                repository.search(nav.animeTitle, source).firstOrNull {
                    nav.animeId.isNotBlank() && it.id == nav.animeId
                } ?: repository.search(nav.animeTitle, source).firstOrNull()
                ?: error("The episode list could not be found")
            }
            repository.details(anime).episodes
        }.onSuccess { episodes ->
            if (episodes.isNotEmpty()) {
                val downloaded = nav.episodes
                val merged = episodes.map { episode ->
                    downloaded.firstOrNull {
                        it.id == episode.id || it.number == episode.number && it.seasonNumber == episode.seasonNumber
                    }?.copy(id = episode.id, number = episode.number, seasonNumber = episode.seasonNumber, title = episode.title)
                        ?: PlaybackEpisode(episode.id, episode.number, episode.seasonNumber, episode.title)
                }
                val oldCurrent = nav.episodes.getOrNull(activeEpisodeIndex)
                val newIndex = merged.indexOfFirst {
                    it.id == nav.currentEpisodeId || oldCurrent != null &&
                        it.number == oldCurrent.number && it.seasonNumber == oldCurrent.seasonNumber
                }.takeIf { it >= 0 } ?: 0
                activeNavigation = nav.copy(currentEpisodeId = merged[newIndex].id, episodes = merged, complete = true)
                activeEpisodeIndex = newIndex
            }
        }
        navigationLoading = false
    }
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            if (!scrubbing) positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            if (++ticks % 8 == 0) onProgress(player.currentPosition, player.duration)
            delay(250)
        }
    }
    LaunchedEffect(controlsVisible, isPlaying, captionMenu, qualityMenu, speedMenu, audioMenu, onlineDialog, controlsLocked) {
        if (controlsVisible && isPlaying && !captionMenu && !qualityMenu && !speedMenu && !audioMenu && !onlineDialog && !controlsLocked) {
            delay(4_500)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekFeedbackSequence) {
        if (seekFeedbackSequence > 0) {
            delay(650)
            seekFeedback = null
        }
    }
    LaunchedEffect(gestureFeedbackSequence) {
        if (gestureFeedbackSequence > 0) {
            delay(800)
            gestureKind = null
        }
    }
    BackHandler {
        if (controlsLocked) { controlsLocked = false; controlsVisible = true }
        else onBack()
    }

    val density = LocalDensity.current
    val activity = context as ComponentActivity
    val brightnessDetector = remember(activity, player, controlsLocked) {
        object : GestureDetector.SimpleOnGestureListener() {
            private var brightness = .5f

            override fun onDown(event: android.view.MotionEvent): Boolean {
                brightness = activity.window.attributes.screenBrightness.takeIf { it >= 0f }
                    ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                return true
            }

            override fun onScroll(
                first: android.view.MotionEvent?, current: android.view.MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (controlsLocked) return false
                if (kotlin.math.abs(distanceY) <= kotlin.math.abs(distanceX)) return false
                val height = playerView?.height?.takeIf { it > 0 } ?: 1
                brightness = (brightness + distanceY / height).coerceIn(.02f, 1f)
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                gestureKind = GestureKind.Brightness
                gestureValue = brightness
                gestureFeedbackSequence++
                controlsVisible = false
                return true
            }

            override fun onSingleTapConfirmed(event: android.view.MotionEvent): Boolean {
                controlsVisible = if (controlsLocked) true else !controlsVisible
                return true
            }

            override fun onDoubleTap(event: android.view.MotionEvent): Boolean {
                if (controlsLocked) return false
                seekBy(-10_000)
                return true
            }
        }.let { GestureDetector(context, it) }
    }
    val volumeDetector = remember(audioManager, player, controlsLocked) {
        object : GestureDetector.SimpleOnGestureListener() {
            private var volume = 0f
            private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

            override fun onDown(event: android.view.MotionEvent): Boolean {
                volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                return true
            }

            override fun onScroll(
                first: android.view.MotionEvent?, current: android.view.MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (controlsLocked) return false
                if (kotlin.math.abs(distanceY) <= kotlin.math.abs(distanceX)) return false
                val height = playerView?.height?.takeIf { it > 0 } ?: 1
                volume = (volume + distanceY / height).coerceIn(0f, 1f)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVolume).roundToInt(), 0)
                gestureKind = GestureKind.Volume
                gestureValue = volume
                gestureFeedbackSequence++
                controlsVisible = false
                return true
            }

            override fun onSingleTapConfirmed(event: android.view.MotionEvent): Boolean {
                controlsVisible = if (controlsLocked) true else !controlsVisible
                return true
            }

            override fun onDoubleTap(event: android.view.MotionEvent): Boolean {
                if (controlsLocked) return false
                seekBy(10_000)
                return true
            }
        }.let { GestureDetector(context, it) }
    }
    val centerDetector = remember(player, controlsLocked) {
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: android.view.MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: android.view.MotionEvent): Boolean {
                controlsVisible = if (controlsLocked) true else !controlsVisible
                return true
            }

            override fun onDoubleTap(event: android.view.MotionEvent): Boolean {
                if (controlsLocked) return false
                if (player.isPlaying) player.pause() else player.play()
                controlsVisible = true
                return true
            }
        }.let { GestureDetector(context, it) }
    }
    val playerModifier = if (screenMode == ScreenMode.Original && videoSize.width > 0 && videoSize.height > 0) {
        val width = (videoSize.width * videoSize.pixelWidthHeightRatio / density.density).dp
        val height = (videoSize.height / density.density).dp
        Modifier.size(width, height)
    } else Modifier.fillMaxSize()
    val chromeVisible = controlsVisible || captionMenu || qualityMenu || speedMenu || audioMenu || navigationLoading

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    playerView = this
                    this.player = player
                    useController = false
                    resizeMode = screenMode.resizeMode
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = false
                }
            },
            update = { view -> view.player = player; view.resizeMode = screenMode.resizeMode },
            modifier = playerModifier.align(Alignment.Center)
        )

        if (!isPictureInPicture) Row(Modifier.fillMaxWidth().fillMaxHeight(.72f).align(Alignment.Center)) {
            Box(
                Modifier.weight(1f).fillMaxHeight().pointerInteropFilter { event -> brightnessDetector.onTouchEvent(event) },
                contentAlignment = Alignment.Center
            ) {
                if (seekFeedback?.startsWith("−") == true) SeekFeedback(seekFeedback.orEmpty())
                if (gestureKind == GestureKind.Brightness) GestureFeedback(Icons.Outlined.Brightness6, "Brightness", gestureValue)
            }
            Box(
                Modifier.weight(.65f).fillMaxHeight().pointerInteropFilter { event -> centerDetector.onTouchEvent(event) }
            )
            Box(
                Modifier.weight(1f).fillMaxHeight().pointerInteropFilter { event -> volumeDetector.onTouchEvent(event) },
                contentAlignment = Alignment.Center
            ) {
                if (seekFeedback?.startsWith("+") == true) SeekFeedback(seekFeedback.orEmpty())
                if (gestureKind == GestureKind.Volume) GestureFeedback(Icons.AutoMirrored.Outlined.VolumeUp, "Volume", gestureValue)
            }
        }

        AnimatedVisibility(chromeVisible && !controlsLocked && !isPictureInPicture, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(.94f), Color.Black.copy(.46f), Color.Transparent)))
                    .padding(horizontal = 20.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerCircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(Teal, CircleShape))
                        Text(" NOW PLAYING", color = Teal, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                        if (captionUri != null) PlayerPill("CC", Violet, Modifier.padding(start = 9.dp))
                        PlayerPill(activeQuality, Teal, Modifier.padding(start = 7.dp))
                    }
                    Text(activeTitle.ifBlank { "Kairo episode" }, color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (activeMeta.isNotBlank()) Text(activeMeta, color = Color.White.copy(.6f), fontSize = 10.sp, maxLines = 1)
                }
                activeNavigation?.let { nav ->
                    Surface(color = Color.Black.copy(.48f), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.1f))) {
                        Text(
                            "${activeEpisodeIndex + 1} / ${nav.episodes.size}", color = Color.White.copy(.78f), fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(chromeVisible && !controlsLocked && !isPictureInPicture, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    val nav = activeNavigation
                    TransportButton(
                        icon = Icons.Outlined.SkipPrevious, label = "Previous", size = 48.dp,
                        enabled = nav != null && activeEpisodeIndex > 0 && !navigationLoading
                    ) { navigateEpisode(-1); controlsVisible = true }
                    TransportButton(Icons.Outlined.Replay10, "Back 10 seconds", 54.dp) { seekBy(-10_000); controlsVisible = true }
                    Surface(
                        onClick = {
                            if (playbackState == Player.STATE_ENDED) player.seekTo(0)
                            if (player.isPlaying) player.pause() else player.play()
                            controlsVisible = true
                        },
                        color = Color.White, shape = CircleShape,
                        shadowElevation = 12.dp,
                        modifier = Modifier.size(72.dp).border(5.dp, Color.White.copy(.18f), CircleShape)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                when {
                                    playbackState == Player.STATE_ENDED -> Icons.Outlined.Replay
                                    isPlaying -> Icons.Outlined.Pause
                                    else -> Icons.Outlined.PlayArrow
                                },
                                if (isPlaying) "Pause" else "Play", tint = Ink, modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    TransportButton(Icons.Outlined.Forward10, "Forward 10 seconds", 54.dp) { seekBy(10_000); controlsVisible = true }
                    TransportButton(
                        icon = Icons.Outlined.SkipNext, label = "Next", size = 48.dp,
                        enabled = nav != null && activeEpisodeIndex < nav.episodes.lastIndex && !navigationLoading
                    ) { navigateEpisode(1); controlsVisible = true }
                }
                if (playbackState == Player.STATE_ENDED) {
                    Text("EPISODE COMPLETE", color = Teal, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 13.dp))
                }
            }
        }

        if ((playbackState == Player.STATE_BUFFERING || navigationLoading) && error == null && !isPictureInPicture) {
            Surface(color = Color.Black.copy(.72f), shape = RoundedCornerShape(18.dp), modifier = Modifier.align(Alignment.Center).padding(top = 130.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Teal)
                    Text(if (navigationLoading) "Preparing episode…" else "Buffering…", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }

        AnimatedVisibility(chromeVisible && !controlsLocked && !isPictureInPicture, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.62f), Color.Black.copy(.96f))))
                    .padding(start = 20.dp, end = 20.dp, top = 34.dp, bottom = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPlaybackTime(if (scrubbing) scrubPositionMs else positionMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Box(Modifier.padding(horizontal = 12.dp).weight(1f).height(30.dp), contentAlignment = Alignment.Center) {
                        if (durationMs > 0) {
                            LinearProgressIndicator(
                                progress = { (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                                color = Color.White.copy(.3f), trackColor = Color.White.copy(.12f)
                            )
                        }
                        Slider(
                            value = (if (scrubbing) scrubPositionMs else positionMs).toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                            onValueChange = { value -> scrubbing = true; scrubPositionMs = value.toLong(); controlsVisible = true },
                            onValueChangeFinished = { player.seekTo(scrubPositionMs); positionMs = scrubPositionMs; scrubbing = false },
                            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                            enabled = durationMs > 0,
                            colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal, inactiveTrackColor = Color.Transparent)
                        )
                    }
                    Text(formatPlaybackTime(durationMs), color = Color.White.copy(.72f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerPill(if (isPlaying) "PLAYING" else if (playbackState == Player.STATE_BUFFERING) "BUFFERING" else "PAUSED", if (isPlaying) Teal else Violet)
                    Spacer(Modifier.weight(1f))
                    if (availableQualities.size > 1) {
                        Box {
                            PlayerAction(Icons.Outlined.HighQuality, activeQuality) { qualityMenu = true; controlsVisible = true }
                            DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }, containerColor = InkRaised) {
                                availableQualities.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label, fontWeight = FontWeight.Bold) },
                                        leadingIcon = { Icon(if (option.label == activeQuality) Icons.Outlined.CheckCircle else Icons.Outlined.HighQuality, null, tint = if (option.label == activeQuality) Teal else Muted) },
                                        onClick = { qualityMenu = false; switchQuality(option); controlsVisible = true }
                                    )
                                }
                            }
                        }
                    }
                    if (audioTracks.size > 1) {
                        Box {
                            PlayerAction(Icons.Outlined.Headphones, "Audio") { audioMenu = true; controlsVisible = true }
                            DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }, containerColor = InkRaised) {
                                audioTracks.forEach { track ->
                                    DropdownMenuItem(
                                        text = { Text(track.label, fontWeight = FontWeight.Bold) },
                                        leadingIcon = { Icon(if (track.selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, tint = if (track.selected) Teal else Muted) },
                                        onClick = { selectTrack(track); audioMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    Box {
                        PlayerAction(Icons.Filled.ClosedCaption, if (captionUri != null) "CC On" else "Captions") { captionMenu = true; controlsVisible = true }
                        DropdownMenu(expanded = captionMenu, onDismissRequest = { captionMenu = false }, containerColor = InkRaised) {
                            subtitleTracks.forEach { track ->
                                DropdownMenuItem(
                                    text = { Text(track.label, fontWeight = FontWeight.Bold) },
                                    leadingIcon = { Icon(if (track.selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, tint = if (track.selected) Teal else Muted) },
                                    onClick = { selectTrack(track); captionMenu = false }
                                )
                            }
                            if (subtitleTracks.isNotEmpty()) HorizontalDivider(color = Color.White.copy(.08f))
                            DropdownMenuItem(
                                text = { Column { Text("Find online captions", fontWeight = FontWeight.Bold); Text("Choose a language and release", color = Muted, fontSize = 10.sp) } },
                                leadingIcon = { Icon(Icons.Outlined.CloudDownload, null, tint = Violet) },
                                onClick = { captionMenu = false; onlineDialog = true; searchOnlineCaptions() }
                            )
                            DropdownMenuItem(
                                text = { Column { Text("Load caption file", fontWeight = FontWeight.Bold); Text("SRT, VTT, ASS, SSA, or TTML", color = Muted, fontSize = 10.sp) } },
                                leadingIcon = { Icon(Icons.Filled.ClosedCaption, null, tint = Teal) },
                                onClick = { captionMenu = false; captionLauncher.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/ttml+xml", "*/*")) }
                            )
                            if (captionUri != null || subtitleTracks.any(PlayerMediaTrack::selected)) DropdownMenuItem(
                                text = { Text("Turn captions off") },
                                leadingIcon = { Icon(Icons.Outlined.SubtitlesOff, null, tint = Danger) },
                                onClick = {
                                    captionMenu = false
                                    val hadExternal = captionUri != null
                                    captionUri = null
                                    captionName = null
                                    if (hadExternal) replaceMediaItem(null)
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                }
                            )
                        }
                    }
                    Box {
                        PlayerAction(Icons.Outlined.Speed, "${formatSpeed(playbackSpeed)}×") { speedMenu = true; controlsVisible = true }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }, containerColor = InkRaised) {
                            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                DropdownMenuItem(
                                    text = { Text("${formatSpeed(speed)}×", fontWeight = FontWeight.Bold) },
                                    leadingIcon = { Icon(if (speed == playbackSpeed) Icons.Outlined.CheckCircle else Icons.Outlined.Speed, null, tint = if (speed == playbackSpeed) Teal else Muted) },
                                    onClick = { playbackSpeed = speed; player.setPlaybackSpeed(speed); speedMenu = false; controlsVisible = true }
                                )
                            }
                        }
                    }
                    PlayerAction(Icons.Filled.FitScreen, screenMode.label) {
                        screenMode = ScreenMode.entries[(screenMode.ordinal + 1) % ScreenMode.entries.size]
                        controlsVisible = true
                    }
                    PlayerAction(Icons.Outlined.PictureInPictureAlt, "PiP", onClick = onPictureInPicture)
                    PlayerAction(Icons.Outlined.Lock, "Lock") { controlsLocked = true; controlsVisible = false }
                }
            }
        }

        AnimatedVisibility(controlsLocked && controlsVisible && !isPictureInPicture, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp)) {
            Surface(
                onClick = { controlsLocked = false; controlsVisible = true },
                color = Color.Black.copy(.82f), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Teal.copy(.55f))
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.LockOpen, "Unlock controls", tint = Teal)
                    Text("UNLOCK", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        error?.let { message ->
            Surface(color = InkRaised.copy(.96f), shape = RoundedCornerShape(20.dp), modifier = Modifier.align(Alignment.Center).padding(30.dp)) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playback problem", color = Danger, style = MaterialTheme.typography.titleLarge)
                    Text(message, color = Muted, modifier = Modifier.padding(top = 8.dp), maxLines = 3)
                    Button({ error = null; player.prepare(); player.play() }, colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Ink), modifier = Modifier.padding(top = 14.dp)) {
                        Text("TRY AGAIN", fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        if (onlineDialog) {
            AlertDialog(
                onDismissRequest = { if (!onlineLoading) onlineDialog = false },
                containerColor = InkRaised,
                title = { Text("Online captions", fontWeight = FontWeight.Black) },
                text = {
                    Column {
                        Text("$searchTitle${if (searchEpisode > 0) " • Episode $searchEpisode" else ""}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                            items(listOf("English", "Hindi", "Urdu", "Arabic", "Japanese", "Spanish", "French", "German")) { language ->
                                FilterChip(
                                    selected = captionLanguage == language,
                                    onClick = { if (!onlineLoading) searchOnlineCaptions(language) },
                                    label = { Text(language, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet, selectedLabelColor = Ink)
                                )
                            }
                        }
                        if (onlineLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet, trackColor = SurfaceBright)
                            Text("Searching and preparing captions…", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                        } else if (onlineError != null) {
                            Text(onlineError.orEmpty(), color = Danger, fontSize = 12.sp, lineHeight = 17.sp)
                            Text("Choose another language above or try another release.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
                        } else {
                            LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(onlineResults, key = { it.url }) { subtitle ->
                                    Surface(
                                        onClick = {
                                            onlineLoading = true
                                            scope.launch {
                                                runCatching { onlineClient.downloadToCache(subtitle) }
                                                    .onSuccess { downloaded ->
                                                        captionName = subtitle.language.uppercase()
                                                        captionUri = downloaded
                                                        replaceMediaItem(downloaded)
                                                        onlineDialog = false
                                                    }
                                                    .onFailure { onlineError = it.message ?: "Caption download failed" }
                                                onlineLoading = false
                                            }
                                        },
                                        color = Surface, shape = RoundedCornerShape(15.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.07f))
                                    ) {
                                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.Subtitles, null, tint = Violet)
                                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                                Text(subtitle.release, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${subtitle.language.uppercase()} • Public OpenSubtitles", color = Muted, fontSize = 9.sp)
                                            }
                                            Icon(Icons.Outlined.Download, null, tint = Teal)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { onlineDialog = false }, enabled = !onlineLoading) { Text("Close") } }
            )
        }
    }
}

@Composable
private fun SeekFeedback(label: String) {
    Surface(color = Color.Black.copy(.72f), shape = CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.18f))) {
        Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
    }
}

@Composable
private fun PlayerCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick, enabled = enabled, color = Color.Black.copy(.55f), shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.13f)), modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = if (enabled) Color.White else Muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick, enabled = enabled, color = Color.Black.copy(if (enabled) .62f else .34f), shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(if (enabled) .15f else .06f)),
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (enabled) Color.White else Muted.copy(.55f), modifier = Modifier.size(size * .48f))
        }
    }
}

@Composable
private fun PlayerPill(label: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(color = tint.copy(.15f), shape = RoundedCornerShape(7.dp), border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(.38f)), modifier = modifier) {
        Text(label, color = tint, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = .5.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), maxLines = 1)
    }
}

@Composable
private fun GestureFeedback(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: Float) {
    Surface(color = Color.Black.copy(.8f), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.16f))) {
        Column(Modifier.width(118.dp).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Teal, modifier = Modifier.size(25.dp))
            Text("${(value * 100).roundToInt()}%", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.padding(top = 5.dp))
            LinearProgressIndicator(
                progress = { value }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(4.dp).clip(CircleShape),
                color = Teal, trackColor = Color.White.copy(.14f)
            )
            Text(label, color = Color.White.copy(.65f), fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun PlayerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        color = Color.Black.copy(if (enabled) .7f else .42f), shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(if (enabled) .16f else .07f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (enabled) Teal else Muted, modifier = Modifier.size(18.dp))
            Text(label, color = if (enabled) Color.White else Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(start = 7.dp), maxLines = 1)
        }
    }
}

private fun episodeLabel(episode: PlaybackEpisode): String = when {
    episode.seasonNumber > 0 -> "S${episode.seasonNumber} · E${episode.number}"
    episode.title.startsWith("Episode", true) -> episode.title
    else -> "Episode ${episode.number}"
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

private fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')

private fun playerTracks(tracks: Tracks, type: Int): List<PlayerMediaTrack> = buildList {
    tracks.groups.filter { it.type == type }.forEach { group ->
        repeat(group.length) { index ->
            if (!group.isTrackSupported(index)) return@repeat
            val format = group.getTrackFormat(index)
            val language = format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
                Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
            val fallback = if (type == C.TRACK_TYPE_AUDIO) "Audio ${size + 1}" else "Subtitle ${size + 1}"
            val details = if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) {
                when (format.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; else -> "${format.channelCount} channels" }
            } else null
            val label = listOfNotNull(format.label?.takeIf(String::isNotBlank), language, details).distinct().joinToString(" • ").ifBlank { fallback }
            add(PlayerMediaTrack(group.mediaTrackGroup, index, type, label, group.isTrackSelected(index)))
        }
    }
}

private fun captionLanguageTag(language: String): String = when {
    language.startsWith("English", true) -> "en"
    language.startsWith("Hindi", true) -> "hi"
    language.startsWith("Urdu", true) -> "ur"
    language.startsWith("Arabic", true) -> "ar"
    language.startsWith("Japanese", true) -> "ja"
    language.startsWith("Spanish", true) -> "es"
    language.startsWith("French", true) -> "fr"
    language.startsWith("German", true) -> "de"
    else -> "und"
}

private fun decodePlaybackNavigation(raw: String): PlaybackNavigation? = runCatching {
    val json = JSONObject(raw)
    val array = json.getJSONArray("episodes")
    PlaybackNavigation(
        animeId = json.optString("animeId"),
        animeTitle = json.optString("animeTitle"),
        animeUrl = json.optString("animeUrl"),
        sourceId = json.optString("sourceId"),
        languageCode = json.optString("languageCode"),
        languageName = json.optString("languageName"),
        currentEpisodeId = json.optString("currentEpisodeId"),
        episodes = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            PlaybackEpisode(
                id = item.getString("id"), number = item.optInt("number"),
                seasonNumber = item.optInt("seasonNumber"), title = item.optString("title"),
                directUri = item.optString("directUri"), recordId = item.optString("recordId"),
                subtitleUri = item.optString("subtitleUri"), qualityLabel = item.optString("qualityLabel")
            )
        },
        complete = json.optBoolean("complete", true)
    )
}.getOrNull()

private fun subtitleMimeType(context: Context, uri: Uri): String {
    val name = displayName(context, uri).lowercase()
    return when {
        name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        name.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        else -> context.contentResolver.getType(uri) ?: MimeTypes.APPLICATION_SUBRIP
    }
}

private fun displayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Captions"
}
