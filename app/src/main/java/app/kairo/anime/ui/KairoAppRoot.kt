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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.work.WorkInfo
import app.kairo.anime.MainViewModel
import app.kairo.anime.data.*
import app.kairo.anime.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

private enum class MainTab(val label: String, val icon: ImageVector) {
    Discover("Discover", Icons.Outlined.Explore), Downloads("Library", Icons.Outlined.DownloadForOffline), Settings("Settings", Icons.Outlined.Tune)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun KairoAppRoot(viewModel: MainViewModel) {
    var tab by remember { mutableStateOf(MainTab.Discover) }
    val context = LocalContext.current
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            viewModel.repository.preferences.downloadTreeUri = uri.toString()
        }
    }

    val openPlayer: (DownloadRecord) -> Unit = { record ->
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
            data = record.uri.toUri()
            putExtra(PlayerActivity.EXTRA_TITLE, "${record.animeTitle} • ${record.episodeLabel}")
            putExtra(PlayerActivity.EXTRA_META, "${record.language} • ${record.quality}")
            putExtra(PlayerActivity.EXTRA_RECORD_ID, record.id)
            putExtra(PlayerActivity.EXTRA_ANIME_TITLE, record.animeTitle)
            putExtra(PlayerActivity.EXTRA_EPISODE_LABEL, record.episodeLabel)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
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
                onToggleWatchlist = { viewModel.toggleWatchlist(viewModel.details!!.anime) }
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
                    MainTab.Settings -> SettingsScreen(viewModel, { folderLauncher.launch(null) }, Modifier.padding(padding))
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
                onDownload = viewModel::startDownload
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
        item(span = { GridItemSpan(maxLineSpan) }) { BrandHeader(currentSource, viewModel.sources.filter { it.enabled }, viewModel::selectSource) }
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
    onToggleWatchlist: () -> Unit
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
                Text(episode.number.toString(), color = Cloud, fontWeight = FontWeight.Black)
            }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text(episode.title.ifBlank { "Episode ${episode.number}" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onDownload: () -> Boolean
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = InkRaised, contentColor = Cloud, dragHandle = { BottomSheetDefaults.DragHandle(color = Muted) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
            Text("Download episode ${state.episode.number}", style = MaterialTheme.typography.headlineMedium)
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
                                if (quality.estimatedBytes > 0) Text("~${formatBytes(quality.estimatedBytes)}", fontSize = 9.sp)
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
                    Text("Estimated download: ~${formatBytes(quality.estimatedBytes)}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 7.dp))
                }
            }
            state.error?.let { Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
            Button(
                onClick = { onDownload() }, enabled = !state.loading && state.selectedQuality != null,
                shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 16.dp)
            ) {
                Icon(Icons.Filled.Download, null)
                Text(if (state.existingDownloads.isEmpty()) "DOWNLOAD" else "DOWNLOAD ANOTHER VERSION", fontWeight = FontWeight.Black, letterSpacing = .7.sp, modifier = Modifier.padding(start = 8.dp))
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
    val telemetry = when {
        bytes <= 0 -> "Connecting to stream…"
        totalBytes > 0 -> "${formatBytes(bytes)} of ~${formatBytes(totalBytes)}  •  ${formatRate(speed)}"
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
                Text("${record.episodeLabel} • ${record.language} • ${record.quality}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
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
private fun SettingsScreen(viewModel: MainViewModel, chooseFolder: () -> Unit, modifier: Modifier = Modifier) {
    var addSource by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    val prefs = viewModel.repository.preferences
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Make it yours", "Storage, audio, and source controls") }
        item { SettingsLabel("DOWNLOADS") }
        item {
            SettingsCard(Icons.Outlined.Folder, "Download folder", prefs.downloadTreeUri?.let { Uri.parse(it).lastPathSegment } ?: "Not selected", Teal, chooseFolder)
        }
        item { SettingsLabel("DEFAULT AUDIO") }
        item {
            Box {
                SettingsCard(Icons.Outlined.Translate, "Preferred language", prefs.defaultLanguage, Violet) { languageExpanded = true }
                DropdownMenu(languageExpanded, { languageExpanded = false }, containerColor = SurfaceBright) {
                    listOf("Japanese", "English", "Hindi", "Spanish", "French", "German", "Arabic", "Urdu").forEach { language ->
                        DropdownMenuItem(text = { Text(language) }, onClick = { prefs.defaultLanguage = language; languageExpanded = false })
                    }
                }
            }
        }
        item { SettingsLabel("CONTENT SOURCES") }
        item {
            Surface(color = Surface, shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(.06f))) {
                Column {
                    viewModel.sources.forEachIndexed { index, source ->
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(if (source.enabled) Teal.copy(.16f) else SurfaceBright), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Hub, null, tint = if (source.enabled) Teal else Muted)
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(source.name, fontWeight = FontWeight.Bold)
                                    if (source.builtIn) LabelPill("BUILT-IN", Violet, Modifier.padding(start = 8.dp))
                                }
                                Text(source.baseUrl, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!source.builtIn) {
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
            ) { Icon(Icons.Filled.Add, null); Text("ADD COMPATIBLE SOURCE", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Black) }
        }
        item {
            Text("Custom sources must expose the same catalog, episode, language, and HLS endpoints as the built-in provider. Kairo validates the catalog before saving it.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
    if (addSource) AddSourceDialog(viewModel, { addSource = false })
}

@Composable
private fun AddSourceDialog(viewModel: MainViewModel, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Surface,
        title = { Text("Add a content source", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Use an AniDB-compatible server you trust.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(name, { name = it }, label = { Text("Source name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, placeholder = { Text("https://example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                viewModel.sourceValidation?.let { Text(it, color = if (it.startsWith("Checking")) Teal else Danger, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.addSource(name, url) { if (it) dismiss() } },
                enabled = name.isNotBlank() && url.startsWith("http"), colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Ink)
            ) { Text("VALIDATE & ADD", fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } }
    )
}

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
    val bitmap by produceState<ImageBitmap?>(MemoryImages.images[url], url) {
        if (value == null && url.isNotBlank()) value = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", app.kairo.anime.data.KairoRepository.USER_AGENT)
                connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
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
