package app.kairo.anime.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.work.WorkInfo
import app.kairo.anime.BuildConfig
import app.kairo.anime.MainViewModel
import app.kairo.anime.data.*
import app.kairo.anime.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

private enum class MainTab(val label: String, val icon: ImageVector) {
    Discover("Discover", Icons.Outlined.Explore), Downloads("Library", Icons.Outlined.DownloadForOffline), Settings("Settings", Icons.Outlined.Tune)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun KairoAppRoot(
    viewModel: MainViewModel,
    incomingAddonUrl: String? = null,
    onAddonUrlConsumed: () -> Unit = {}
) {
    var tab by remember { mutableStateOf(MainTab.Discover) }
    val context = LocalContext.current
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            viewModel.repository.preferences.downloadTreeUri = uri.toString()
        }
    }
    val mediaFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            viewModel.localFolderChanged(uri.toString())
        }
    }

    LaunchedEffect(incomingAddonUrl) {
        if (!incomingAddonUrl.isNullOrBlank()) tab = MainTab.Settings
    }

    val openUri: (String, String, String, String, String, String, String, List<String>, List<String>, String, PlaybackNavigation?) -> Unit =
        { uri, title, meta, recordId, referer, authToken, subtitleUri, qualityLabels, qualityUrls, selectedQuality, navigation ->
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
            data = uri.toUri()
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_META, meta)
            putExtra(PlayerActivity.EXTRA_RECORD_ID, recordId)
            putExtra(PlayerActivity.EXTRA_REFERER, referer)
            putExtra(PlayerActivity.EXTRA_AUTH_TOKEN, authToken)
            putExtra(PlayerActivity.EXTRA_SUBTITLE_URI, subtitleUri)
            putStringArrayListExtra(PlayerActivity.EXTRA_QUALITY_LABELS, ArrayList(qualityLabels))
            putStringArrayListExtra(PlayerActivity.EXTRA_QUALITY_URLS, ArrayList(qualityUrls))
            putExtra(PlayerActivity.EXTRA_SELECTED_QUALITY, selectedQuality)
            navigation?.let { putExtra(PlayerActivity.EXTRA_NAVIGATION, encodePlaybackNavigation(it)) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    val openPlayer: (DownloadRecord) -> Unit = { record ->
        val episodeKey: (DownloadRecord) -> String = {
            it.episodeId.ifBlank { "download:${it.id}" }
        }
        val siblings = viewModel.completedDownloads
            .filter {
                (record.animeId.isNotBlank() && it.animeId == record.animeId && it.sourceId == record.sourceId ||
                    record.animeId.isBlank() && it.animeTitle.equals(record.animeTitle, true)) &&
                    it.language.equals(record.language, true)
            }
            .sortedWith(compareBy<DownloadRecord>({ it.seasonNumber }, { it.episodeNumber }, { it.completedAt }))
            .groupBy {
                if (it.episodeNumber > 0) "${it.seasonNumber}:${it.episodeNumber}"
                else it.episodeId.ifBlank { it.episodeLabel.lowercase() }
            }
            .values.map { versions -> versions.firstOrNull { it.quality == record.quality } ?: versions.first() }
        val navigation = PlaybackNavigation(
            animeId = record.animeId,
            animeTitle = record.animeTitle,
            animeUrl = record.animeUrl,
            sourceId = record.sourceId,
            languageCode = record.language,
            languageName = record.language,
            currentEpisodeId = episodeKey(record),
            episodes = siblings.map {
                PlaybackEpisode(
                    id = episodeKey(it), number = it.episodeNumber, seasonNumber = it.seasonNumber,
                    title = it.episodeLabel, directUri = it.uri, recordId = it.id,
                    subtitleUri = it.subtitleUri, qualityLabel = it.quality
                )
            },
            complete = false
        )
        openUri(
            record.uri, "${record.animeTitle} • ${record.episodeLabel}", "${record.language} • ${record.quality}",
            record.id, "", "", record.subtitleUri, listOf(record.quality), listOf(record.uri), record.quality, navigation
        )
    }

    LaunchedEffect(viewModel.playRequest) {
        viewModel.playRequest?.let { request ->
            openUri(
                request.uri, request.title, request.meta, "", request.referer, request.authToken, "",
                request.qualities.map(QualityOption::label), request.qualities.map(QualityOption::url), request.selectedQualityLabel,
                request.navigation
            )
            viewModel.consumePlayRequest()
        }
    }

    BackHandler(enabled = viewModel.downloadChoice != null || viewModel.details != null || tab != MainTab.Discover) {
        when {
            viewModel.downloadChoice != null -> viewModel.dismissChoice()
            viewModel.details != null -> viewModel.closeDetails()
            else -> tab = MainTab.Discover
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (viewModel.details != null) {
            DetailScreen(
                details = viewModel.details!!,
                loading = viewModel.detailsLoading,
                onBack = viewModel::closeDetails,
                onEpisode = { viewModel.chooseEpisode(viewModel.details!!.anime, it) },
                downloadsFor = { viewModel.downloadsFor(viewModel.details!!.anime, it) },
                watchlisted = viewModel.isWatchlisted(viewModel.details!!.anime),
                onToggleWatchlist = { viewModel.toggleWatchlist(viewModel.details!!.anime) },
                onFindPlayable = { anime -> viewModel.findPlayable(anime); tab = MainTab.Discover }
            )
        } else {
            Scaffold(
                containerColor = Ink,
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                bottomBar = {
                    KairoNavigation(tab) { tab = it }
                }
            ) { padding ->
                when (tab) {
                    MainTab.Discover -> DiscoverScreen(viewModel, Modifier.padding(padding))
                    MainTab.Downloads -> LibraryScreen(
                        active = viewModel.activeDownloads,
                        completed = viewModel.completedDownloads,
                        progress = viewModel.playbackProgress,
                        watchlist = viewModel.watchlist,
                        onPlay = openPlayer,
                        onAnime = viewModel::openDetails,
                        onDelete = { viewModel.deleteDownload(it, true) },
                        onCancel = viewModel::cancelDownload,
                        modifier = Modifier.padding(padding)
                    )
                    MainTab.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        chooseFolder = { folderLauncher.launch(null) },
                        chooseMediaFolder = { mediaFolderLauncher.launch(null) },
                        incomingAddonUrl = incomingAddonUrl,
                        onAddonUrlConsumed = onAddonUrlConsumed,
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }

        viewModel.downloadChoice?.let { choice ->
            DownloadChoiceSheet(
                state = choice,
                onDismiss = viewModel::dismissChoice,
                onLanguage = viewModel::selectLanguage,
                onQuality = viewModel::selectQuality,
                onPlayExisting = { record -> viewModel.dismissChoice(); openPlayer(record) },
                onPlayNow = viewModel::playSelected,
                onDownload = viewModel::startDownload,
                instantPlaybackEnabled = viewModel.instantPlaybackEnabled
            )
        }
    }
}

@Composable
private fun KairoNavigation(selected: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar(
        containerColor = InkRaised,
        tonalElevation = 0.dp,
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
    ) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, null) },
                label = { Text(tab.label, fontWeight = FontWeight.SemiBold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Ink, selectedTextColor = Teal,
                    indicatorColor = Teal, unselectedIconColor = Muted, unselectedTextColor = Muted
                )
            )
        }
    }
}

@Composable
private fun DiscoverScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state = viewModel.home
    val currentSource = viewModel.repository.selectedSource()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(164.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BrandHeader(currentSource, viewModel.sources.filter { it.enabled && it.canBrowse() }, viewModel::selectSource)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SearchField(state.query, viewModel::search)
        }
        if (state.loading && state.anime.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { LoadingPane("Curating your next watch…") }
        } else if (state.error != null && state.anime.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { ErrorPane(state.error, viewModel::refreshHome) }
        } else {
            state.anime.firstOrNull()?.let { featured ->
                item(span = { GridItemSpan(maxLineSpan) }) { FeaturedCard(featured) { viewModel.openDetails(featured) } }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle(if (state.query.isBlank()) "This season" else "Search results", "${state.anime.size} titles")
            }
            items(state.anime.drop(1), key = { "${it.sourceId}-${it.id}" }) { anime ->
                AnimeCard(anime) { viewModel.openDetails(anime) }
            }
        }
    }
}

@Composable
private fun BrandHeader(current: SourceDefinition, sources: List<SourceDefinition>, onSource: (SourceDefinition) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(
                Brush.linearGradient(listOf(Teal, Violet, Amber), start = Offset.Zero, end = Offset(140f, 140f))
            ), contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = Ink, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.padding(start = 13.dp).weight(1f)) {
            Text("KAIRO", style = MaterialTheme.typography.headlineMedium, letterSpacing = 1.sp)
            Text("ANIME, ON YOUR TIME", color = Teal, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp)
        }
        Box {
            Surface(
                onClick = { expanded = true }, color = Surface, shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.08f))
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Teal))
                    Text(current.name, Modifier.padding(start = 7.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(17.dp), tint = Muted)
                }
            }
            DropdownMenu(expanded, { expanded = false }, containerColor = SurfaceBright) {
                sources.forEach { source ->
                    DropdownMenuItem(
                        text = { Text(source.name) },
                        leadingIcon = { Icon(if (source.id == current.id) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (source.id == current.id) Teal else Muted) },
                        onClick = { onSource(source); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text("Search anime, movies, or series", color = Muted) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Teal) },
        trailingIcon = {
            AnimatedVisibility(query.isNotBlank()) {
                IconButton({ onQuery("") }) { Icon(Icons.Filled.Close, null, tint = Muted) }
            }
        },
        shape = RoundedCornerShape(20.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Surface, unfocusedContainerColor = Surface,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = Cloud, unfocusedTextColor = Cloud, cursorColor = Teal
        ),
        modifier = Modifier.fillMaxWidth().border(1.dp, Teal.copy(.22f), RoundedCornerShape(20.dp))
    )
}

@Composable
private fun FeaturedCard(anime: Anime, onClick: () -> Unit) {
    Card(
        onClick = onClick, shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.42f)) {
            RemoteImage(anime.imageUrl, anime.title, Modifier.fillMaxSize(), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Ink.copy(.96f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp).fillMaxWidth(.85f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabelPill("FEATURED", Teal)
                    if (anime.score.isNotBlank()) Text("★ ${anime.score}", color = Amber, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                }
                Text(anime.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 13.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Cloud).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Ink, modifier = Modifier.size(19.dp))
                            Text("View episodes", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }
                    Text(anime.type, color = Muted, modifier = Modifier.padding(start = 14.dp), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AnimeCard(anime: Anime, onClick: () -> Unit) {
    Card(
        onClick = onClick, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(.72f)) {
            RemoteImage(anime.imageUrl, anime.title, Modifier.fillMaxSize(), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Ink.copy(.9f)))))
            if (anime.score.isNotBlank()) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Ink.copy(.82f)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text("★ ${anime.score}", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                Text(anime.type.uppercase(), color = Teal, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text(anime.title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun DetailScreen(
    details: AnimeDetails,
    loading: Boolean,
    onBack: () -> Unit,
    onEpisode: (Episode) -> Unit,
    downloadsFor: (Episode) -> List<DownloadRecord>,
    watchlisted: Boolean,
    onToggleWatchlist: () -> Unit,
    onFindPlayable: (Anime) -> Unit
) {
    Box(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    RemoteImage(details.anime.imageUrl, details.anime.title, Modifier.fillMaxSize(), ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.copy(.15f), Ink.copy(.3f), Ink))))
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(14.dp).size(48.dp).clip(CircleShape).background(Ink.copy(.72f)).border(1.dp, Color.White.copy(.12f), CircleShape)
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Cloud) }
                    FilledIconButton(
                        onClick = onToggleWatchlist,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (watchlisted) Teal else Ink.copy(.72f),
                            contentColor = if (watchlisted) Ink else Cloud
                        ),
                        modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).size(48.dp)
                            .border(1.dp, Color.White.copy(.12f), CircleShape)
                    ) {
                        Icon(if (watchlisted) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd, if (watchlisted) "Remove from watchlist" else "Add to watchlist")
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 22.dp)) {
                        LabelPill(details.status.ifBlank { "SERIES" }.uppercase(), Teal)
                        Text(details.anime.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (details.genres.isNotEmpty()) Text(details.genres.joinToString("  •  "), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (details.description.isNotBlank()) item {
                Text(details.description, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
            }
            if (details.sourceNotice.isNotBlank()) item {
                Surface(
                    color = Violet.copy(.10f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(.28f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, tint = Violet)
                        Text(details.sourceNotice, color = Cloud, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 12.dp).weight(1f))
                        if (!loading && details.episodes.isEmpty()) {
                            FilledTonalButton(
                                onClick = { onFindPlayable(details.anime) },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Violet, contentColor = Ink),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) { Text("FIND", fontSize = 10.sp, fontWeight = FontWeight.Black) }
                        }
                    }
                }
            }
            item { SectionTitle("Episodes", if (loading) "Loading…" else "${details.episodes.size} available", Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
            if (loading) item { LoadingPane("Finding every available language…") }
            items(details.episodes, key = { it.id }) { episode ->
                EpisodeRow(episode, downloadsFor(episode)) { onEpisode(episode) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, existing: List<DownloadRecord>, onClick: () -> Unit) {
    Surface(
        onClick = onClick, color = Surface, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.06f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Violet.copy(.28f), Teal.copy(.18f)))), contentAlignment = Alignment.Center) {
                Text(
                    if (episode.seasonNumber > 0) "S${episode.seasonNumber}\nE${episode.number}" else episode.number.toString(),
                    color = Cloud, fontWeight = FontWeight.Black, fontSize = if (episode.seasonNumber > 0) 10.sp else 14.sp,
                    lineHeight = 12.sp
                )
            }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text(episode.title.ifBlank { episode.label }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        existing.isNotEmpty() -> "Downloaded • ${existing.joinToString { it.quality }}"
                        episode.filler -> "Filler • choose audio & quality"
                        else -> "Choose audio & quality"
                    },
                    color = when { existing.isNotEmpty() -> Teal; episode.filler -> Amber; else -> Muted },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Icon(if (existing.isNotEmpty()) Icons.Filled.CheckCircle else Icons.Outlined.DownloadForOffline, null, tint = Teal)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadChoiceSheet(
    state: app.kairo.anime.DownloadChoiceState,
    onDismiss: () -> Unit,
    onLanguage: (LanguageOption) -> Unit,
    onQuality: (QualityOption) -> Unit,
    onPlayExisting: (DownloadRecord) -> Unit,
    onPlayNow: () -> Boolean,
    onDownload: () -> Boolean,
    instantPlaybackEnabled: Boolean
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = InkRaised, contentColor = Cloud, dragHandle = { BottomSheetDefaults.DragHandle(color = Muted) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
            Text(state.episode.label, style = MaterialTheme.typography.headlineMedium)
            Text(state.anime.title, color = Muted, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.existingDownloads.isNotEmpty()) {
                Surface(
                    color = Teal.copy(.1f), shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Teal.copy(.28f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Teal.copy(.18f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.CheckCircle, null, tint = Teal)
                        }
                        Column(Modifier.padding(horizontal = 11.dp).weight(1f)) {
                            Text("Already in your library", fontWeight = FontWeight.ExtraBold)
                            Text(state.existingDownloads.joinToString("  •  ") { "${it.language} ${it.quality}" }, color = Muted, fontSize = 10.sp, maxLines = 2)
                        }
                        FilledTonalButton(
                            onClick = { onPlayExisting(state.existingDownloads.first()) },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Teal, contentColor = Ink),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(17.dp)); Text("WATCH", fontSize = 10.sp, fontWeight = FontWeight.Black) }
                    }
                }
            }
            Text("AUDIO LANGUAGE", color = Teal, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
            if (state.languages.isEmpty() && state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal, trackColor = SurfaceBright)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(state.languages) { language ->
                    FilterChip(
                        selected = state.selectedLanguage == language,
                        onClick = { onLanguage(language) },
                        label = { Text(language.label) },
                        leadingIcon = { Text(languageFlag(language.code), fontSize = 16.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Teal, selectedLabelColor = Ink),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = state.selectedLanguage == language, borderColor = Color.White.copy(.12f))
                    )
                }
            }
            Text("VIDEO QUALITY", color = Violet, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            if (state.loading && state.selectedLanguage != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet, trackColor = SurfaceBright)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(state.qualities) { quality ->
                    FilterChip(
                        selected = state.selectedQuality == quality,
                        onClick = { onQuality(quality) },
                        label = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(quality.label, fontWeight = FontWeight.Bold)
                                if (quality.estimatedBytes > 0) Text(
                                    (if (quality.delivery == DeliveryKind.HLS) "~" else "") + formatBytes(quality.estimatedBytes),
                                    fontSize = 9.sp
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet, selectedLabelColor = Ink),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = state.selectedQuality == quality, borderColor = Color.White.copy(.12f))
                    )
                }
            }
            state.selectedQuality?.takeIf { it.estimatedBytes > 0 }?.let { quality ->
                Row(Modifier.padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Storage, null, tint = Violet, modifier = Modifier.size(16.dp))
                    Text(
                        when (quality.delivery) {
                            DeliveryKind.HLS -> "Estimated download: ~${formatBytes(quality.estimatedBytes)}"
                            DeliveryKind.DIRECT -> "Original file size: ${formatBytes(quality.estimatedBytes)}"
                            DeliveryKind.LOCAL -> "File size: ${formatBytes(quality.estimatedBytes)}"
                        },
                        color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 7.dp)
                    )
                }
            }
            state.error?.let { Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
            val selectedDelivery = state.selectedQuality?.delivery
            if (selectedDelivery == DeliveryKind.LOCAL || (instantPlaybackEnabled && selectedDelivery != null)) {
                Button(
                    onClick = { onPlayNow() }, enabled = !state.loading,
                    shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 16.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text(if (selectedDelivery == DeliveryKind.LOCAL) "PLAY FROM DEVICE" else "WATCH NOW", fontWeight = FontWeight.Black, letterSpacing = .7.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (selectedDelivery != DeliveryKind.LOCAL) {
                Button(
                    onClick = { onDownload() }, enabled = !state.loading && state.selectedQuality != null,
                    shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 10.dp)
                ) {
                    Icon(Icons.Filled.Download, null)
                    Text(if (state.existingDownloads.isEmpty()) "DOWNLOAD" else "DOWNLOAD ANOTHER VERSION", fontWeight = FontWeight.Black, letterSpacing = .7.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (!instantPlaybackEnabled && selectedDelivery != null && selectedDelivery != DeliveryKind.LOCAL) {
                Text(
                    "Download-first mode is on. Enable Instant playback in Settings to also show Watch now.",
                    color = Muted, fontSize = 10.sp, lineHeight = 15.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    active: List<WorkInfo>,
    completed: List<DownloadRecord>,
    progress: List<PlaybackProgress>,
    watchlist: List<WatchlistEntry>,
    onPlay: (DownloadRecord) -> Unit,
    onAnime: (Anime) -> Unit,
    onDelete: (DownloadRecord) -> Unit,
    onCancel: (UUID) -> Unit,
    modifier: Modifier = Modifier
) {
    val progressById = progress.associateBy { it.recordId }
    val recent = progress.filter { it.percent in 1..94 }
        .mapNotNull { item -> completed.firstOrNull { it.id == item.recordId }?.let { it to item } }.take(12)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Your library", "Downloads, watchlist, and everything in progress") }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Continue watching", "${recent.size} in progress", Modifier.padding(top = 8.dp)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    items(recent, key = { it.first.id }) { (record, item) -> ContinueWatchingCard(record, item) { onPlay(record) } }
                }
            }
        }
        if (watchlist.isNotEmpty()) {
            item { SectionTitle("My watchlist", "${watchlist.size} saved", Modifier.padding(top = 8.dp)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    items(watchlist, key = { "${it.anime.sourceId}-${it.anime.id}" }) { entry -> WatchlistCard(entry.anime) { onAnime(entry.anime) } }
                }
            }
        }
        if (active.isNotEmpty()) {
            item { SectionTitle("Downloading", "${active.size} active", Modifier.padding(top = 8.dp)) }
            items(active, key = { it.id }) { info -> ActiveDownloadCard(info) { onCancel(info.id) } }
        }
        item { SectionTitle("Ready to watch", "${completed.size} episodes", Modifier.padding(top = 10.dp)) }
        if (completed.isEmpty() && active.isEmpty() && watchlist.isEmpty()) item { EmptyLibrary() }
        items(completed, key = { it.id }) { record ->
            DownloadCard(record, progressById[record.id], { onPlay(record) }, { onDelete(record) })
        }
    }
}

@Composable
private fun ContinueWatchingCard(record: DownloadRecord, progress: PlaybackProgress, onPlay: () -> Unit) {
    Surface(onClick = onPlay, color = Surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.width(240.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Teal.copy(.16f))) {
        Column {
            Box(Modifier.fillMaxWidth().height(112.dp)) {
                RemoteImage(record.animeImageUrl, record.animeTitle, Modifier.fillMaxSize(), ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(.82f)))))
                FilledIconButton(onClick = onPlay, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Teal, contentColor = Ink), modifier = Modifier.align(Alignment.Center)) {
                    Icon(Icons.Filled.PlayArrow, "Continue")
                }
                LabelPill("${progress.percent}% WATCHED", Teal, Modifier.align(Alignment.BottomStart).padding(10.dp))
            }
            Column(Modifier.padding(13.dp)) {
                Text(record.animeTitle, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(record.episodeLabel, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                LinearProgressIndicator({ progress.percent / 100f }, Modifier.fillMaxWidth().padding(top = 9.dp).height(4.dp).clip(CircleShape), color = Teal, trackColor = SurfaceBright)
            }
        }
    }
}

@Composable
private fun WatchlistCard(anime: Anime, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.width(132.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.74f)) {
            RemoteImage(anime.imageUrl, anime.title, Modifier.fillMaxSize(), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(.92f)))))
            Icon(Icons.Filled.Bookmark, null, tint = Teal, modifier = Modifier.align(Alignment.TopEnd).padding(9.dp).size(19.dp))
            Text(anime.title, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(11.dp))
        }
    }
}

@Composable
private fun ActiveDownloadCard(info: WorkInfo, onCancel: () -> Unit) {
    val progress = info.progress.getInt("progress", 0)
    val title = info.progress.getString("title") ?: "Preparing download"
    val bytes = info.progress.getLong("bytes", 0)
    val totalBytes = info.progress.getLong("totalBytes", 0)
    val speed = info.progress.getLong("bytesPerSecond", 0)
    val totalIsEstimate = info.progress.getBoolean("totalIsEstimate", true)
    val telemetry = when {
        bytes <= 0 -> "Connecting to stream…"
        totalBytes > 0 -> "${formatBytes(bytes)} of ${if (totalIsEstimate) "~" else ""}${formatBytes(totalBytes)}  •  ${formatRate(speed)}"
        else -> "${formatBytes(bytes)}  •  ${formatRate(speed)}"
    }
    Surface(color = Surface, shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(.18f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Violet.copy(.18f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Downloading, null, tint = Violet) }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(telemetry, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("$progress%", color = Violet, fontWeight = FontWeight.Black)
                IconButton(onCancel, Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Cancel download", tint = Muted) }
            }
            if (progress == 0) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp).height(5.dp).clip(CircleShape), color = Violet, trackColor = SurfaceBright)
            else LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().padding(top = 14.dp).height(5.dp).clip(CircleShape), color = Violet, trackColor = SurfaceBright)
        }
    }
}

@Composable
private fun DownloadCard(record: DownloadRecord, progress: PlaybackProgress?, onPlay: () -> Unit, onDelete: () -> Unit) {
    Surface(onClick = onPlay, color = Surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.06f))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Teal, Violet))), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Ink, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text(record.animeTitle, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${record.episodeLabel} • ${record.language} • ${record.quality}${if (record.subtitleUri.isNotBlank()) " • CC" else ""}",
                    color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                )
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatBytes(record.bytes), color = Teal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    progress?.takeIf { it.percent > 0 }?.let { Text("  •  ${it.percent}% watched", color = Violet, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
                progress?.takeIf { it.percent in 1..99 }?.let {
                    LinearProgressIndicator({ it.percent / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(CircleShape), color = Teal, trackColor = SurfaceBright)
                }
            }
            IconButton(onDelete) { Icon(Icons.Outlined.Delete, "Delete", tint = Danger) }
        }
    }
}

@Composable
private fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    chooseFolder: () -> Unit,
    chooseMediaFolder: () -> Unit,
    incomingAddonUrl: String? = null,
    onAddonUrlConsumed: () -> Unit = {}
) {
    var addSource by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    val prefs = viewModel.repository.preferences
    LaunchedEffect(incomingAddonUrl) {
        if (!incomingAddonUrl.isNullOrBlank()) addSource = true
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Make it yours", "Storage, audio, and source controls") }
        item { SettingsLabel("DOWNLOADS") }
        item {
            SettingsCard(Icons.Outlined.Folder, "Download folder", prefs.downloadTreeUri?.let { Uri.parse(it).lastPathSegment } ?: "Not selected", Teal, chooseFolder)
        }
        item { SettingsLabel("PERSONAL LIBRARY") }
        item {
            SettingsCard(
                Icons.Outlined.VideoLibrary,
                "On-device anime folder",
                prefs.localMediaTreeUri?.let { Uri.parse(it).lastPathSegment } ?: "Choose a folder containing your anime",
                Violet,
                chooseMediaFolder
            )
        }
        item { SettingsLabel("DEFAULT AUDIO") }
        item {
            Box {
                SettingsCard(Icons.Outlined.Translate, "Preferred language", prefs.defaultLanguage, Violet) { languageExpanded = true }
                DropdownMenu(languageExpanded, { languageExpanded = false }, containerColor = SurfaceBright) {
                    listOf("Japanese", "English", "Hindi Dubbed", "Spanish", "French", "German", "Arabic", "Urdu").forEach { language ->
                        DropdownMenuItem(text = { Text(language) }, onClick = { prefs.defaultLanguage = language; languageExpanded = false })
                    }
                }
            }
        }
        item { SettingsLabel("PLAYBACK") }
        item {
            SettingsSwitchCard(
                icon = Icons.Outlined.PlayCircle,
                title = "Instant playback",
                subtitle = if (viewModel.instantPlaybackEnabled) "On • choose Watch now or Download" else "Off • download before watching",
                tint = Teal,
                checked = viewModel.instantPlaybackEnabled,
                onCheckedChange = viewModel::setInstantPlayback
            )
        }
        item { SettingsLabel("CONTENT SOURCES") }
        item {
            Surface(color = Surface, shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.06f))) {
                Column {
                    viewModel.sources.forEachIndexed { index, source ->
                        val customSources = viewModel.sources.filterNot { it.builtIn }
                        val customIndex = customSources.indexOfFirst { it.id == source.id }
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(if (source.enabled) Teal.copy(.16f) else SurfaceBright), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Hub, null, tint = if (source.enabled) Teal else Muted)
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(source.name, fontWeight = FontWeight.Bold)
                                    LabelPill(sourceKindLabel(source.kind), if (source.kind == SourceKind.ANILIST) Violet else Teal, Modifier.padding(start = 8.dp))
                                }
                                Text(sourceDescription(source, prefs.localMediaTreeUri), color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (!source.builtIn) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                        onClick = { viewModel.moveSource(source.id, -1) },
                                        enabled = customIndex > 0,
                                        modifier = Modifier.size(28.dp)
                                    ) { Icon(Icons.Filled.KeyboardArrowUp, "Move up", Modifier.size(18.dp), tint = if (customIndex > 0) Muted else Muted.copy(.25f)) }
                                    IconButton(
                                        onClick = { viewModel.moveSource(source.id, 1) },
                                        enabled = customIndex in 0 until customSources.lastIndex,
                                        modifier = Modifier.size(28.dp)
                                    ) { Icon(Icons.Filled.KeyboardArrowDown, "Move down", Modifier.size(18.dp), tint = if (customIndex in 0 until customSources.lastIndex) Muted else Muted.copy(.25f)) }
                                }
                                Switch(source.enabled, { viewModel.toggleSource(source.id) }, colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Teal))
                                IconButton({ viewModel.removeSource(source.id) }) { Icon(Icons.Outlined.Close, null, tint = Danger) }
                            }
                        }
                        if (index != viewModel.sources.lastIndex) HorizontalDivider(color = Color.White.copy(.05f))
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { addSource = true }, shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Teal.copy(.5f)),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Icon(Icons.Filled.Add, null); Text("ADD A SOURCE", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Black) }
        }
        item {
            Text("Add-on order is stream priority: move faster or preferred resolvers upward. Catalog-only add-ons power discovery while stream add-ons are checked together. Kairo plays direct HTTP(S) media and safely skips torrent-only results. Only add services you trust and may use.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        item { SettingsLabel("ABOUT") }
        item {
            AboutCard(
                version = BuildConfig.VERSION_NAME,
                build = BuildConfig.VERSION_CODE
            )
        }
    }
    if (addSource) AddSourceDialog(
        viewModel = viewModel,
        initialUrl = incomingAddonUrl.orEmpty(),
        dismiss = { addSource = false; onAddonUrlConsumed() }
    )
}

@Composable
private fun AddSourceDialog(viewModel: MainViewModel, initialUrl: String = "", dismiss: () -> Unit) {
    var kind by remember { mutableStateOf(SourceKind.STREMIO) }
    var name by remember { mutableStateOf("") }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Surface,
        title = { Text("Add a content source", fontWeight = FontWeight.Black) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Install a Stremio add-on from its manifest, connect Jellyfin, or use a Kairo-compatible endpoint.", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == SourceKind.STREMIO,
                        onClick = { kind = SourceKind.STREMIO },
                        label = { Text("Stremio") },
                        leadingIcon = { Icon(Icons.Outlined.Extension, null, Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Teal, selectedLabelColor = Ink)
                    )
                    FilterChip(
                        selected = kind == SourceKind.JELLYFIN,
                        onClick = { kind = SourceKind.JELLYFIN },
                        label = { Text("Jellyfin") },
                        leadingIcon = { Icon(Icons.Outlined.Dns, null, Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Teal, selectedLabelColor = Ink)
                    )
                }
                FilterChip(
                    selected = kind == SourceKind.KAIRO_COMPATIBLE,
                    onClick = { kind = SourceKind.KAIRO_COMPATIBLE },
                    label = { Text("Kairo compatible") },
                    leadingIcon = { Icon(Icons.Outlined.Hub, null, Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet, selectedLabelColor = Ink)
                )
                if (kind != SourceKind.STREMIO) {
                    OutlinedTextField(name, { name = it }, label = { Text("Source name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    url, { url = it },
                    label = { Text(if (kind == SourceKind.STREMIO) "Add-on or manifest URL" else if (kind == SourceKind.JELLYFIN) "Server URL" else "Base URL") },
                    placeholder = { Text(if (kind == SourceKind.STREMIO) "https://addon.example/manifest.json" else "https://example.com") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (kind == SourceKind.JELLYFIN) {
                    OutlinedTextField(
                        token, { token = it }, label = { Text("Access token / API key") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text("Use HTTPS outside your home network. The token is stored only on this device.", color = Muted, fontSize = 10.sp)
                }
                Surface(color = SurfaceBright, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            when (kind) {
                                SourceKind.STREMIO -> "HOW TO INSTALL AN ADD-ON"
                                SourceKind.JELLYFIN -> "SERVER URL EXAMPLES"
                                else -> "COMPATIBLE URL EXAMPLES"
                            },
                            color = if (kind == SourceKind.KAIRO_COMPATIBLE) Violet else Teal,
                            fontWeight = FontWeight.Black, fontSize = 9.sp
                        )
                        if (kind == SourceKind.STREMIO) {
                            Text("1. Open the add-on's configuration page if it has one.", color = Cloud, fontSize = 10.sp)
                            Text("2. Copy its Install / manifest URL and paste it above.", color = Cloud, fontSize = 10.sp)
                            Text("3. Kairo reads its name and capabilities automatically.", color = Cloud, fontSize = 10.sp)
                            Text("Both https://…/manifest.json and stremio://… links work. Personalized URLs may contain private service keys, so do not share screenshots of them.", color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
                        } else if (kind == SourceKind.JELLYFIN) {
                            Text("Home Wi-Fi:  http://192.168.1.50:8096", color = Cloud, fontSize = 10.sp)
                            Text("Tailscale/VPN:  http://100.64.0.10:8096", color = Cloud, fontSize = 10.sp)
                            Text("Reverse proxy:  https://jellyfin.yourdomain.com", color = Cloud, fontSize = 10.sp)
                            Text("Replace the address with your own Jellyfin server; create the key in Jellyfin Dashboard → API Keys.", color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
                        } else {
                            Text("Built in:  https://anidb.app  (already connected)", color = Cloud, fontSize = 10.sp)
                            Text("Home server:  http://192.168.1.50:3000", color = Cloud, fontSize = 10.sp)
                            Text("Hosted server:  https://anime.yourdomain.com", color = Cloud, fontSize = 10.sp)
                            Text("The last two are address patterns, not public services. They work only after a Kairo-compatible server is deployed there.", color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
                        }
                    }
                }
                viewModel.sourceValidation?.let {
                    val working = it.startsWith("Checking") || it.startsWith("Connecting") || it.startsWith("Reading")
                    Text(it, color = if (working) Teal else Danger, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val done: (Boolean) -> Unit = { if (it) dismiss() }
                    when (kind) {
                        SourceKind.STREMIO -> viewModel.addStremioSource(url, done)
                        SourceKind.JELLYFIN -> viewModel.addJellyfinSource(name, url, token, done)
                        else -> viewModel.addSource(name, url, done)
                    }
                },
                enabled = url.contains("://") && (kind == SourceKind.STREMIO || name.isNotBlank()) && (kind != SourceKind.JELLYFIN || token.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Ink)
            ) { Text("VALIDATE & ADD", fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } }
    )
}

private fun sourceKindLabel(kind: SourceKind): String = when (kind) {
    SourceKind.KAIRO_COMPATIBLE -> "STREAM"
    SourceKind.STREMIO -> "ADD-ON"
    SourceKind.ANILIST -> "DISCOVERY"
    SourceKind.JELLYFIN -> "JELLYFIN"
    SourceKind.LOCAL -> "LOCAL"
}

private fun sourceDescription(source: SourceDefinition, localTreeUri: String?): String = when (source.kind) {
    SourceKind.ANILIST -> "Rich metadata and discovery • find playback from another source"
    SourceKind.JELLYFIN -> "${source.baseUrl} • your personal media server"
    SourceKind.LOCAL -> localTreeUri?.let { "Folder: ${Uri.parse(it).lastPathSegment}" } ?: "Choose your anime folder above"
    SourceKind.KAIRO_COMPATIBLE -> source.baseUrl
    SourceKind.STREMIO -> buildString {
        val capabilities = source.addonResources.split(',').filter(String::isNotBlank)
            .joinToString(" + ") { it.replaceFirstChar(Char::uppercase) }
        append(capabilities.ifBlank { "Stremio protocol" })
        source.addonVersion.takeIf(String::isNotBlank)?.let { append(" • v").append(it) }
        Uri.parse(source.baseUrl).host?.let { append(" • ").append(it) }
        if (source.addonP2p) append(" • P2P manifest")
    }
}

private fun SourceDefinition.canBrowse(): Boolean = kind != SourceKind.STREMIO || addonResources.split(',').contains("catalog")

@Composable
private fun AboutCard(version: String, build: Int) {
    Surface(color = Surface, shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.06f))) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Violet.copy(.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Info, null, tint = Violet)
            }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text("Kairo for Android", fontWeight = FontWeight.Bold)
                Text("Version $version • build $build", color = Muted, fontSize = 11.sp)
            }
            LabelPill("CURRENT", Teal)
        }
    }
}

private fun encodePlaybackNavigation(navigation: PlaybackNavigation): String = JSONObject().apply {
    put("animeId", navigation.animeId)
    put("animeTitle", navigation.animeTitle)
    put("animeUrl", navigation.animeUrl)
    put("sourceId", navigation.sourceId)
    put("languageCode", navigation.languageCode)
    put("languageName", navigation.languageName)
    put("currentEpisodeId", navigation.currentEpisodeId)
    put("complete", navigation.complete)
    put("episodes", JSONArray().apply {
        navigation.episodes.forEach { episode ->
            put(JSONObject().apply {
                put("id", episode.id)
                put("number", episode.number)
                put("seasonNumber", episode.seasonNumber)
                put("title", episode.title)
                put("directUri", episode.directUri)
                put("recordId", episode.recordId)
                put("subtitleUri", episode.subtitleUri)
                put("qualityLabel", episode.qualityLabel)
            })
        }
    })
}.toString()

@Composable
private fun SettingsCard(icon: ImageVector, title: String, subtitle: String, tint: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Surface, shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.06f))) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(tint.copy(.15f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
private fun SettingsSwitchCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) }, color = Surface, shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.06f))
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(tint.copy(.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint)
            }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            Switch(
                checked = checked, onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Teal)
            )
        }
    }
}

@Composable private fun SettingsLabel(text: String) { Text(text, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(trailing, color = Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LabelPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(.16f)).border(1.dp, color.copy(.26f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = .8.sp)
    }
}

@Composable
private fun LoadingPane(text: String) {
    Column(Modifier.fillMaxWidth().height(210.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Teal, trackColor = SurfaceBright)
        Text(text, color = Muted, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun ErrorPane(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(230.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.CloudOff, null, tint = Danger, modifier = Modifier.size(38.dp))
        Text(message, color = Muted, modifier = Modifier.padding(16.dp), maxLines = 3)
        Button(retry, colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Ink)) { Text("TRY AGAIN", fontWeight = FontWeight.Black) }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(Modifier.fillMaxWidth().padding(vertical = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(28.dp)).background(Surface), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.VideoLibrary, null, tint = Violet, modifier = Modifier.size(38.dp)) }
        Text("Your offline shelf is empty", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.padding(top = 18.dp))
        Text("Choose an episode and Kairo will keep it here.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

private object MemoryImages {
    val images = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(60, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 60
    })
}

@Composable
private fun RemoteImage(url: String, description: String, modifier: Modifier, scale: ContentScale) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(MemoryImages.images[url], url) {
        if (value == null && url.isNotBlank()) value = withContext(Dispatchers.IO) {
            runCatching {
                val decoded = if (url.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(url))?.use(BitmapFactory::decodeStream)
                } else {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                    connection.setRequestProperty("User-Agent", app.kairo.anime.data.KairoRepository.USER_AGENT)
                    val repository = (context.applicationContext as app.kairo.anime.KairoApp).repository
                    repository.imageHeaders(url).forEach(connection::setRequestProperty)
                    connection.inputStream.use(BitmapFactory::decodeStream)
                }
                decoded?.asImageBitmap()
            }.getOrNull()?.also { MemoryImages.images[url] = it }
        }
    }
    if (bitmap != null) androidx.compose.foundation.Image(bitmap!!, description, modifier, contentScale = scale)
    else Box(modifier.background(Brush.linearGradient(listOf(SurfaceBright, Surface)))) {
        Icon(Icons.Outlined.Movie, null, tint = Muted.copy(.4f), modifier = Modifier.size(40.dp).align(Alignment.Center))
    }
}

private fun languageFlag(code: String): String = when (code.lowercase().take(2)) {
    "en" -> "🇬🇧"; "hi" -> "🇮🇳"; "ja" -> "🇯🇵"; "es" -> "🇪🇸"; "fr" -> "🇫🇷"; "de" -> "🇩🇪"; "ar" -> "🇸🇦"; "ur" -> "🇵🇰"; else -> "🌐"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    else -> "${bytes / 1024} KB"
}

private fun formatRate(bytesPerSecond: Long): String = if (bytesPerSecond <= 0) "Starting…" else "${formatBytes(bytesPerSecond)}/s"
