package app.kairo.anime.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.kairo.anime.data.*
import java.security.MessageDigest

class LocalFolderAdapter(
    private val context: Context,
    private val preferences: KairoPreferences
) : AnimeSourceAdapter {
    override val kind = SourceKind.LOCAL

    override suspend fun browse(source: SourceDefinition): List<Anime> = groups(source).map(LocalGroup::anime)

    override suspend fun search(query: String, source: SourceDefinition): List<Anime> =
        browse(source).filter { query.isBlank() || it.title.contains(query, true) }

    override suspend fun details(anime: Anime, source: SourceDefinition): AnimeDetails {
        val group = groups(source).firstOrNull { it.anime.id == anime.id } ?: error("This local folder is no longer available")
        val files = (if (group.directFilesOnly) {
            group.directory.listFiles().filter(::isVideo).map { LocalVideo(it, 0) }
        } else collectVideos(group.directory)).sortedWith(
            compareBy<LocalVideo>({ it.season }, { episodeNumber(it.file.name.orEmpty()) ?: Int.MAX_VALUE }, { it.file.name })
        )
        val episodes = files.mapIndexed { index, video ->
            val name = video.file.name.orEmpty()
            Episode(video.file.uri.toString(), episodeNumber(name) ?: index + 1, name.substringBeforeLast('.'), seasonNumber = video.season)
        }
        return AnimeDetails(
            anime = group.anime,
            description = "Videos discovered inside ${group.directory.name ?: "your selected folder"}. Kairo reads these files in place and never copies or deletes them.",
            status = "ON DEVICE", genres = listOf("Personal library"), episodes = episodes,
            sourceNotice = "Already stored on this device. Tap an episode to play it with Kairo's player."
        )
    }

    override suspend fun languages(episode: Episode, source: SourceDefinition): List<LanguageOption> =
        listOf(LanguageOption("mul", "Embedded audio tracks", episode.id, "All tracks"))

    override suspend fun qualities(language: LanguageOption, source: SourceDefinition): List<QualityOption> {
        val uri = Uri.parse(language.embedUrl)
        val file = DocumentFile.fromSingleUri(context, uri) ?: error("The local video is unavailable")
        val extension = file.name.orEmpty().substringAfterLast('.', "mp4").lowercase()
        return listOf(QualityOption("On device", uri.toString(), file.length(), delivery = DeliveryKind.LOCAL, container = extension))
    }

    private fun groups(source: SourceDefinition): List<LocalGroup> {
        val tree = preferences.localMediaTreeUri?.let(Uri::parse) ?: error("Choose a local media folder in Settings first")
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("Kairo no longer has access to the selected media folder")
        val children = root.listFiles().toList()
        val rootVideos = children.filter(::isVideo)
        val directories = children.filter(DocumentFile::isDirectory).filter { collectVideos(it, limit = 1).isNotEmpty() }
        val seasonLayout = rootVideos.isEmpty() && directories.isNotEmpty() && directories.all { seasonNumber(it.name.orEmpty()) > 0 }
        val groupDirectories = if (seasonLayout) listOf(root) else directories
        return buildList {
            if (rootVideos.isNotEmpty()) add(createGroup(root, source, directFilesOnly = true))
            groupDirectories.forEach { add(createGroup(it, source)) }
        }.distinctBy { it.anime.id }.sortedBy { it.anime.title.lowercase() }
    }

    private fun createGroup(directory: DocumentFile, source: SourceDefinition, directFilesOnly: Boolean = false): LocalGroup {
        val title = directory.name?.takeIf(String::isNotBlank) ?: "Local videos"
        val cover = findCover(directory)?.uri?.toString().orEmpty()
        val anime = Anime(
            id = stableId(directory.uri.toString()), title = title, imageUrl = cover,
            url = directory.uri.toString(), type = "LOCAL", sourceId = source.id
        )
        return LocalGroup(anime, directory, directFilesOnly)
    }

    private fun findCover(directory: DocumentFile): DocumentFile? = directory.listFiles().firstOrNull { file ->
        file.isFile && file.name.orEmpty().substringBeforeLast('.').lowercase() in setOf("cover", "poster", "folder") &&
            (file.type?.startsWith("image/") == true || file.name.orEmpty().substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS)
    }

    private fun collectVideos(directory: DocumentFile, inheritedSeason: Int = 0, limit: Int = 1_000): List<LocalVideo> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<LocalVideo>()
        for (child in directory.listFiles()) {
            if (result.size >= limit) break
            when {
                isVideo(child) -> result += LocalVideo(child, inheritedSeason)
                child.isDirectory -> {
                    val childSeason = seasonNumber(child.name.orEmpty()).takeIf { it > 0 } ?: inheritedSeason
                    result += collectVideos(child, childSeason, limit - result.size)
                }
            }
        }
        return result
    }

    private fun isVideo(file: DocumentFile): Boolean = file.isFile && (
        file.type?.startsWith("video/") == true || file.name.orEmpty().substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS
    )

    private fun episodeNumber(name: String): Int? = listOf(
        Regex("[Ss]\\d{1,2}[Ee](\\d{1,4})"),
        Regex("(?:episode|ep|e)[ ._-]*(\\d{1,4})", RegexOption.IGNORE_CASE),
        Regex("(?:^|[ ._-])(\\d{1,3})(?:[ ._-]|$)")
    ).firstNotNullOfOrNull { it.find(name)?.groupValues?.get(1)?.toIntOrNull() }

    private fun seasonNumber(name: String): Int = Regex("(?:season|s)[ ._-]*(\\d{1,2})", RegexOption.IGNORE_CASE)
        .find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(10).joinToString("") { "%02x".format(it) }

    private data class LocalGroup(val anime: Anime, val directory: DocumentFile, val directFilesOnly: Boolean)
    private data class LocalVideo(val file: DocumentFile, val season: Int)

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "m4v", "mov", "avi", "ts", "m2ts")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
