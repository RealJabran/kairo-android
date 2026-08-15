package app.kairo.anime.data

data class Anime(
    val id: String,
    val title: String,
    val imageUrl: String,
    val url: String,
    val score: String = "",
    val type: String = "TV",
    val sourceId: String = "anidb"
)

data class AnimeDetails(
    val anime: Anime,
    val description: String = "",
    val status: String = "",
    val genres: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val sourceNotice: String = ""
)

data class Episode(
    val id: String,
    val number: Int,
    val title: String = "",
    val filler: Boolean = false,
    val seasonNumber: Int = 0
) {
    val label: String
        get() = if (seasonNumber > 0) "S${seasonNumber} · E$number" else "Episode $number"
}

data class LanguageOption(
    val code: String,
    val name: String,
    val embedUrl: String,
    val label: String = languageLabel(code, name)
)

enum class DeliveryKind { HLS, DIRECT, LOCAL }

data class QualityOption(
    val label: String,
    val url: String,
    val estimatedBytes: Long = 0,
    val bandwidthBitsPerSecond: Long = 0,
    val delivery: DeliveryKind = DeliveryKind.HLS,
    val container: String = "mp4",
    val hlsAudioUrl: String = ""
)

data class PlaybackEpisode(
    val id: String,
    val number: Int,
    val seasonNumber: Int = 0,
    val title: String = "",
    val directUri: String = "",
    val recordId: String = "",
    val subtitleUri: String = "",
    val qualityLabel: String = ""
)

data class PlaybackNavigation(
    val animeId: String,
    val animeTitle: String,
    val animeUrl: String = "",
    val sourceId: String,
    val languageCode: String,
    val languageName: String,
    val currentEpisodeId: String,
    val episodes: List<PlaybackEpisode>,
    val complete: Boolean = true
)

enum class SourceKind { KAIRO_COMPATIBLE, STREMIO, ANILIST, JELLYFIN, LOCAL }

data class SourceDefinition(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val kind: SourceKind = SourceKind.KAIRO_COMPATIBLE,
    val authToken: String = "",
    val userId: String = "",
    val addonId: String = "",
    val addonVersion: String = "",
    val addonDescription: String = "",
    val addonResources: String = "",
    val addonP2p: Boolean = false
)

data class DownloadRecord(
    val id: String,
    val animeTitle: String,
    val episodeLabel: String,
    val language: String,
    val quality: String,
    val uri: String,
    val bytes: Long,
    val completedAt: Long,
    val animeId: String = "",
    val animeImageUrl: String = "",
    val animeUrl: String = "",
    val sourceId: String = "anidb",
    val episodeNumber: Int = 0,
    val episodeId: String = "",
    val seasonNumber: Int = 0,
    val subtitleUri: String = ""
)

data class PlaybackProgress(
    val recordId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val percent: Int
        get() = if (durationMs <= 0) 0 else ((positionMs * 100L) / durationMs).toInt().coerceIn(0, 100)
}

data class WatchlistEntry(val anime: Anime, val addedAt: Long)

fun languageLabel(code: String, fallback: String): String {
    val normalized = code.lowercase()
    return when {
        normalized.startsWith("en") || normalized == "eng" -> "English"
        normalized.startsWith("hi") || normalized == "hin" || fallback.contains("hindi", true) -> "Hindi Dubbed"
        normalized.startsWith("ja") || normalized == "jpn" -> "Japanese"
        normalized.startsWith("es") || normalized == "spa" -> "Spanish"
        normalized.startsWith("fr") || normalized == "fra" -> "French"
        normalized.startsWith("de") || normalized == "deu" -> "German"
        normalized.startsWith("pt") || normalized == "por" -> "Portuguese"
        normalized.startsWith("ar") || normalized == "ara" -> "Arabic"
        normalized.startsWith("ur") || normalized == "urd" -> "Urdu"
        else -> fallback.ifBlank { code.uppercase() }
    }
}
