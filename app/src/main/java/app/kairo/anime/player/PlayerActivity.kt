package app.kairo.anime.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.kairo.anime.data.KairoPreferences
import app.kairo.anime.data.PlaybackProgress
import app.kairo.anime.ui.*
import kotlinx.coroutines.delay

class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var preferences: KairoPreferences
    private var recordId = ""

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
        val startPosition = preferences.playbackProgress().firstOrNull { it.recordId == recordId }
            ?.takeIf { it.percent in 1..94 }?.positionMs ?: 0
        setContent {
            KairoTheme {
                PlayerScreen(
                    uri = uri.toString(), title = title, meta = meta, startPositionMs = startPosition,
                    referer = referer, authToken = authToken,
                    onBack = { finish() }, onPlayer = { player = it }, onProgress = ::persistProgress,
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && player?.isPlaying == true) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
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
        const val EXTRA_ANIME_TITLE = "anime_title"
        const val EXTRA_EPISODE_LABEL = "episode_label"
    }
}

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
    onBack: () -> Unit,
    onPlayer: (ExoPlayer) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onPlaybackActive: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var screenMode by remember { mutableStateOf(ScreenMode.Fit) }
    var screenMenu by remember { mutableStateOf(false) }
    var captionMenu by remember { mutableStateOf(false) }
    var captionUri by remember { mutableStateOf<Uri?>(null) }
    var captionName by remember { mutableStateOf<String?>(null) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }

    fun buildMediaItem(subtitle: Uri?): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        if (subtitle != null) {
            val configuration = MediaItem.SubtitleConfiguration.Builder(subtitle)
                .setMimeType(subtitleMimeType(context, subtitle))
                .setLanguage("und")
                .setLabel(captionName ?: "External captions")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
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
            setMediaItem(MediaItem.fromUri(uri))
            if (startPositionMs > 0) seekTo(startPositionMs)
            playWhenReady = true
            prepare()
            addListener(object : Player.Listener {
                override fun onPlayerError(playerError: PlaybackException) {
                    error = playerError.localizedMessage ?: "This video could not be decoded"
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) error = null
                    if (playbackState == Player.STATE_ENDED) onProgress(duration, duration)
                    onPlaybackActive(playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED)
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
        player.setMediaItem(buildMediaItem(subtitle), position)
        player.prepare()
        player.playWhenReady = shouldPlay
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
    LaunchedEffect(player) {
        while (true) {
            delay(2_000)
            onProgress(player.currentPosition, player.duration)
        }
    }
    BackHandler(onBack = onBack)

    val density = LocalDensity.current
    val playerModifier = if (screenMode == ScreenMode.Original && videoSize.width > 0 && videoSize.height > 0) {
        val width = (videoSize.width * videoSize.pixelWidthHeightRatio / density.density).dp
        val height = (videoSize.height / density.density).dp
        Modifier.size(width, height)
    } else Modifier.fillMaxSize()
    val chromeVisible = controlsVisible || screenMenu || captionMenu

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 3_500
                    controllerHideOnTouch = true
                    controllerAutoShow = true
                    resizeMode = screenMode.resizeMode
                    setShowSubtitleButton(true)
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> controlsVisible = visibility == View.VISIBLE })
                    keepScreenOn = false
                }
            },
            update = { view -> view.player = player; view.resizeMode = screenMode.resizeMode },
            modifier = playerModifier.align(Alignment.Center)
        )

        AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(.9f), Color.Transparent))).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onBack, Modifier.size(44.dp).background(Color.Black.copy(.48f), CircleShape).border(1.dp, Color.White.copy(.12f), CircleShape)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.padding(start = 13.dp).weight(1f)) {
                    Text("NOW PLAYING", color = Teal, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                    Text(title.ifBlank { "Kairo episode" }, color = Color.White, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (meta.isNotBlank()) Text(meta, color = Color.White.copy(.58f), fontSize = 10.sp)
                }
            }
        }

        AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 72.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    PlayerAction(Icons.Filled.ClosedCaption, captionName?.let { "Captions on" } ?: "Captions") { captionMenu = true }
                    DropdownMenu(expanded = captionMenu, onDismissRequest = { captionMenu = false }, containerColor = InkRaised) {
                        DropdownMenuItem(
                            text = { Column { Text("Load caption file", fontWeight = FontWeight.Bold); Text("SRT, VTT, ASS, SSA, or TTML", color = Muted, fontSize = 10.sp) } },
                            leadingIcon = { Icon(Icons.Filled.ClosedCaption, null, tint = Teal) },
                            onClick = {
                                captionMenu = false
                                captionLauncher.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/ttml+xml", "*/*"))
                            }
                        )
                        if (captionUri != null) DropdownMenuItem(
                            text = { Text("Remove loaded captions") },
                            onClick = { captionMenu = false; captionUri = null; captionName = null; replaceMediaItem(null) }
                        )
                    }
                }
                Box {
                    PlayerAction(Icons.Filled.FitScreen, screenMode.label) { screenMenu = true }
                    DropdownMenu(expanded = screenMenu, onDismissRequest = { screenMenu = false }, containerColor = InkRaised) {
                        ScreenMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Column { Text(mode.label, fontWeight = FontWeight.Bold); Text(screenModeDescription(mode), color = Muted, fontSize = 10.sp) } },
                                leadingIcon = { Icon(if (mode == screenMode) Icons.Filled.Check else Icons.Filled.AspectRatio, null, tint = if (mode == screenMode) Teal else Muted) },
                                onClick = { screenMode = mode; screenMenu = false }
                            )
                        }
                    }
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
    }
}

@Composable
private fun PlayerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Black.copy(.7f), shape = RoundedCornerShape(13.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.16f))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Teal, modifier = Modifier.size(18.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(start = 7.dp), maxLines = 1)
        }
    }
}

private fun screenModeDescription(mode: ScreenMode): String = when (mode) {
    ScreenMode.Fit -> "Show the whole picture"
    ScreenMode.Crop -> "Fill the screen and crop edges"
    ScreenMode.Stretch -> "Stretch to every edge"
    ScreenMode.Original -> "Display at native pixel size"
}

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
