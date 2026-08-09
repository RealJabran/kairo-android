package app.kairo.anime.data.source

import android.net.Uri
import android.util.Base64
import app.kairo.anime.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class StremioConnection(
    val manifestUrl: String,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val resources: Set<String>,
    val p2p: Boolean,
    val configurationRequired: Boolean
)

/**
 * A small, native Stremio protocol client. Kairo intentionally consumes only direct HTTP(S)
 * media URLs. Torrent hashes and external web pages need transports Kairo does not provide and
 * are ignored instead of being handed to the player as broken links.
 */
class StremioAddonAdapter(private val preferences: KairoPreferences) : AnimeSourceAdapter {
    override val kind = SourceKind.STREMIO

    private data class TimedManifest(val value: AddonManifest, val loadedAt: Long)
    private data class TimedStreams(val value: List<AddonStream>, val loadedAt: Long)
    private val manifestCache = ConcurrentHashMap<String, TimedManifest>()
    private val streamCache = ConcurrentHashMap<String, TimedStreams>()

    override suspend fun browse(source: SourceDefinition): List<Anime> {
        val manifest = manifest(source)
        val catalogs = browsableCatalogs(manifest)
        if (catalogs.isEmpty()) {
            error("${manifest.name} is a resolver add-on. Keep it enabled for streams, then browse from a catalog add-on.")
        }
        return coroutineScope {
            catalogs.take(MAX_CATALOGS).map { catalog ->
                async(Dispatchers.IO) {
                    runCatching { catalog(source, catalog) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { "${it.type}:${it.id}" }.take(MAX_TITLES)
        }
    }

    override suspend fun search(query: String, source: SourceDefinition): List<Anime> {
        if (query.isBlank()) return browse(source)
        val manifest = manifest(source)
        val searchable = manifest.catalogs.filter { catalog ->
            catalog.type in SUPPORTED_TYPES && catalog.extras.any { it.name == "search" }
        }
        if (searchable.isEmpty()) {
            return browse(source).filter { it.title.contains(query, true) }
        }
        return coroutineScope {
            searchable.take(MAX_CATALOGS).map { catalog ->
                async(Dispatchers.IO) {
                    runCatching { catalog(source, catalog, query) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { "${it.type}:${it.id}" }.take(MAX_TITLES)
        }
    }

    override suspend fun details(anime: Anime, source: SourceDefinition): AnimeDetails {
        val ref = animeRef(anime)
        val sourceManifest = manifest(source)
        val meta = runCatching {
            if (!sourceManifest.supports("meta", ref.type, ref.id)) error("Metadata is not advertised")
            meta(source, ref)
        }.recoverCatching {
            if (!ref.id.startsWith("tt")) throw it
            val response = JSONObject(SourceHttp.get("https://v3-cinemeta.strem.io/meta/${segment(ref.type)}/${segment(ref.id)}.json"))
            response.getJSONObject("meta")
        }.getOrElse {
            if (ref.type == "movie") {
                return AnimeDetails(
                    anime = anime,
                    episodes = listOf(Episode(encodeVideoRef(ref), 1, anime.title)),
                    sourceNotice = "Metadata was unavailable, but enabled resolver add-ons will still be checked for a playable stream."
                )
            }
            error("${source.name} did not return episode metadata for ${anime.title}")
        }

        val mappedAnime = mapAnime(meta, source, ref.type)
        val videos = meta.optJSONArray("videos").objects()
        val episodes = if (videos.isNotEmpty()) {
            videos.mapIndexedNotNull { index, video ->
                val videoId = video.optString("id").takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
                Episode(
                    id = encodeVideoRef(VideoRef(ref.type, videoId)),
                    number = video.optInt("episode").takeIf { it > 0 } ?: index + 1,
                    title = video.optString("title"),
                    seasonNumber = video.optInt("season")
                )
            }.sortedWith(compareBy<Episode>({ it.seasonNumber }, { it.number }))
        } else {
            listOf(Episode(encodeVideoRef(VideoRef(ref.type, ref.id)), 1, mappedAnime.title))
        }
        return AnimeDetails(
            anime = mappedAnime,
            description = meta.optString("description"),
            status = listOf(meta.optString("releaseInfo"), meta.optString("runtime")).filter(String::isNotBlank).joinToString(" • "),
            genres = meta.optJSONArray("genres").strings().take(8),
            episodes = episodes,
            sourceNotice = "Catalog by ${source.name}. Direct streams are resolved across all enabled Stremio add-ons in your priority order."
        )
    }

    override suspend fun languages(episode: Episode, source: SourceDefinition): List<LanguageOption> {
        val ref = decodeVideoRef(episode.id)
        val streams = streams(ref)
        if (streams.isEmpty()) {
            error("No direct HTTPS stream was returned. Torrent-only results are not playable in Kairo.")
        }
        return streams.groupBy(AddonStream::language).keys
            .sortedBy { LANGUAGE_ORDER.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            .map { code ->
                val name = streamLanguageName(code)
                LanguageOption(code, name, encodeLanguageRef(ref, code), name)
            }
    }

    override suspend fun qualities(language: LanguageOption, source: SourceDefinition): List<QualityOption> {
        val selection = decodeLanguageRef(language.embedUrl)
        return streams(selection.ref).filter { it.language == selection.language }
            .sortedWith(
                compareBy<AddonStream>({ it.height }, { -it.sourcePriority }, { if (it.cached) 1 else 0 })
            )
            .map { stream ->
                QualityOption(
                    label = "${stream.quality} • ${stream.sourceName}",
                    url = stream.url,
                    estimatedBytes = stream.bytes,
                    delivery = if (stream.hls) DeliveryKind.HLS else DeliveryKind.DIRECT,
                    container = stream.container
                )
            }.distinctBy(QualityOption::url).take(MAX_STREAMS)
    }

    fun validate(rawUrl: String): StremioConnection {
        val manifestUrl = normalizeManifestUrl(rawUrl)
        val parsed = parseManifest(JSONObject(SourceHttp.get(manifestUrl, timeoutMs = MANIFEST_TIMEOUT_MS)), manifestUrl)
        require(parsed.id.isNotBlank() && parsed.name.isNotBlank()) { "This is not a valid Stremio add-on manifest" }
        require(parsed.resources.isNotEmpty()) { "The manifest does not advertise any supported resources" }
        require(parsed.resources.any { it.name in setOf("catalog", "meta", "stream") }) {
            "This add-on only provides subtitles. Kairo already includes online subtitle search in the player."
        }
        require(!parsed.configurationRequired) {
            "This add-on must be configured first. Open its configuration page, then paste the personalized Install URL."
        }
        manifestCache[manifestUrl] = TimedManifest(parsed, System.currentTimeMillis())
        return StremioConnection(
            manifestUrl = manifestUrl,
            id = parsed.id,
            name = parsed.name,
            version = parsed.version,
            description = parsed.description,
            resources = parsed.resources.map(ResourceSpec::name).toSet(),
            p2p = parsed.p2p,
            configurationRequired = parsed.configurationRequired
        )
    }

    private fun manifest(source: SourceDefinition): AddonManifest {
        val url = normalizeManifestUrl(source.baseUrl)
        val cached = manifestCache[url]
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < MANIFEST_TTL_MS) return cached.value
        return parseManifest(JSONObject(SourceHttp.get(url, timeoutMs = MANIFEST_TIMEOUT_MS)), url).also {
            manifestCache[url] = TimedManifest(it, System.currentTimeMillis())
        }
    }

    private fun parseManifest(json: JSONObject, url: String): AddonManifest {
        val types = json.optJSONArray("types").strings().toSet()
        val prefixes = json.optJSONArray("idPrefixes").strings()
        val resources = json.optJSONArray("resources").values().mapNotNull { item ->
            when (item) {
                is String -> ResourceSpec(item, types, prefixes)
                is JSONObject -> ResourceSpec(
                    name = item.optString("name"),
                    types = item.optJSONArray("types").strings().toSet().ifEmpty { types },
                    prefixes = item.optJSONArray("idPrefixes").strings().ifEmpty { prefixes }
                )
                else -> null
            }
        }.filter { it.name in PROTOCOL_RESOURCES }
        val catalogs = json.optJSONArray("catalogs").objects().mapNotNull { item ->
            val type = item.optString("type")
            val id = item.optString("id")
            if (type.isBlank() || id.isBlank()) return@mapNotNull null
            val modernExtras = item.optJSONArray("extra").objects().associate {
                it.optString("name") to it.optBoolean("isRequired")
            }.filterKeys(String::isNotBlank)
            val legacySupported = item.optJSONArray("extraSupported").strings()
            val legacyRequired = item.optJSONArray("extraRequired").strings().toSet()
            val extraNames = (modernExtras.keys + legacySupported + legacyRequired).distinct()
            CatalogSpec(
                type = type,
                id = id,
                name = item.optString("name", id),
                extras = extraNames.map { name ->
                    ExtraSpec(name, modernExtras[name] == true || name in legacyRequired)
                }
            )
        }
        val hints = json.optJSONObject("behaviorHints")
        return AddonManifest(
            url = url,
            id = json.optString("id"),
            name = json.optString("name"),
            version = json.optString("version"),
            description = json.optString("description"),
            types = types,
            prefixes = prefixes,
            resources = resources,
            catalogs = catalogs,
            p2p = hints?.optBoolean("p2p") == true,
            configurationRequired = hints?.optBoolean("configurationRequired") == true
        )
    }

    private fun browsableCatalogs(manifest: AddonManifest): List<CatalogSpec> = manifest.catalogs
        .filter { catalog ->
            catalog.type in SUPPORTED_TYPES && catalog.extras.none { it.required && it.name != "skip" }
        }
        .sortedByDescending { catalog ->
            listOf(catalog.id, catalog.name).any { it.contains("anime", true) || it.contains("kitsu", true) }
        }

    private fun catalog(source: SourceDefinition, catalog: CatalogSpec, search: String? = null): List<Anime> {
        val extra = search?.let { "search=${segment(it)}" }
        val url = endpoint(source.baseUrl, "catalog", catalog.type, catalog.id, extra)
        val response = JSONObject(SourceHttp.get(url, source.baseUrl, timeoutMs = RESOURCE_TIMEOUT_MS))
        val metas = response.optJSONArray("metas") ?: response.optJSONArray("metasDetailed")
        return metas.objects().mapNotNull { item ->
            val id = item.optString("id")
            val title = item.optString("name")
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            mapAnime(item, source, item.optString("type", catalog.type))
        }
    }

    private fun meta(source: SourceDefinition, ref: VideoRef): JSONObject {
        val response = JSONObject(SourceHttp.get(endpoint(source.baseUrl, "meta", ref.type, ref.id), source.baseUrl, timeoutMs = RESOURCE_TIMEOUT_MS))
        val value = response.opt("meta")
        return when (value) {
            is JSONObject -> value
            is JSONArray -> value.optJSONObject(0) ?: error("Empty metadata response")
            else -> error("Invalid metadata response")
        }
    }

    private fun mapAnime(item: JSONObject, source: SourceDefinition, fallbackType: String): Anime {
        val id = item.optString("id")
        val type = item.optString("type", fallbackType).lowercase().ifBlank { fallbackType.lowercase() }
        val score = item.optString("imdbRating").takeUnless { it == "0" || it == "0.0" }.orEmpty()
        return Anime(
            id = id,
            title = item.optString("name", "Untitled"),
            imageUrl = item.optString("poster").ifBlank { item.optString("background") },
            url = "stremio://$type/${encodeText(id)}",
            score = score,
            type = type.uppercase(Locale.US),
            sourceId = source.id
        )
    }

    private suspend fun streams(ref: VideoRef): List<AddonStream> {
        val sources = preferences.sources().filter { it.enabled && it.kind == SourceKind.STREMIO }
        val signature = sources.joinToString("|") { it.id + ":" + it.baseUrl }
        val cacheKey = "${ref.type}|${ref.id}|${signature.hashCode()}"
        streamCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.loadedAt < STREAM_TTL_MS }?.let { return it.value }
        val results = coroutineScope {
            sources.mapIndexed { priority, source ->
                async(Dispatchers.IO) {
                    runCatching {
                        val addonManifest = manifest(source)
                        if (!addonManifest.supports("stream", ref.type, ref.id)) return@runCatching emptyList()
                        val json = JSONObject(SourceHttp.get(
                            endpoint(source.baseUrl, "stream", ref.type, ref.id),
                            source.baseUrl,
                            timeoutMs = STREAM_TIMEOUT_MS
                        ))
                        json.optJSONArray("streams").objects().mapNotNull { item ->
                            mapStream(item, source.name, priority)
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy(AddonStream::url)
        }
        streamCache[cacheKey] = TimedStreams(results, System.currentTimeMillis())
        return results
    }

    private fun mapStream(item: JSONObject, sourceName: String, sourcePriority: Int): AddonStream? {
        val url = item.optString("url")
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return null
        val hints = item.optJSONObject("behaviorHints")
        val descriptive = listOf(
            item.optString("name"), item.optString("title"), item.optString("description"), hints?.optString("filename").orEmpty()
        ).filter(String::isNotBlank).joinToString(" ")
        val height = qualityHeight(descriptive)
        val quality = when {
            height > 0 -> "${height}p"
            url.substringBefore('?').endsWith(".m3u8", true) -> "Auto"
            else -> "Original"
        }
        val filename = hints?.optString("filename").orEmpty()
        val extension = (filename.ifBlank { Uri.parse(url).lastPathSegment.orEmpty() })
            .substringBefore('?').substringAfterLast('.', "mp4").lowercase()
            .takeIf { it in VIDEO_CONTAINERS } ?: "mp4"
        val normalized = descriptive.lowercase()
        return AddonStream(
            url = url,
            sourceName = sourceName,
            sourcePriority = sourcePriority,
            quality = quality,
            height = height,
            language = detectLanguage(normalized),
            bytes = hints?.optLong("videoSize")?.takeIf { it > 0 } ?: item.optLong("size").takeIf { it > 0 } ?: 0,
            container = extension,
            hls = url.substringBefore('?').endsWith(".m3u8", true) || normalized.contains(" hls"),
            cached = listOf("cached", "instant", "[rd+]", "[ad+]").any(normalized::contains)
        )
    }

    private fun AddonManifest.supports(resource: String, type: String, id: String): Boolean = resources.any { spec ->
        spec.name == resource && (spec.types.isEmpty() || type in spec.types) &&
            (spec.prefixes.isEmpty() || spec.prefixes.any(id::startsWith))
    }

    private fun animeRef(anime: Anime): VideoRef {
        val uri = runCatching { Uri.parse(anime.url) }.getOrNull()
        if (uri?.scheme == "stremio") {
            val type = uri.host.orEmpty().ifBlank { anime.type.lowercase() }
            val encoded = uri.pathSegments.firstOrNull().orEmpty()
            return VideoRef(type, decodeText(encoded))
        }
        return VideoRef(anime.type.lowercase().let { if (it == "tv") "series" else it }, anime.id)
    }

    private fun encodeVideoRef(ref: VideoRef): String = "stx:${encodeJson(JSONObject().put("type", ref.type).put("id", ref.id))}"

    private fun decodeVideoRef(value: String): VideoRef {
        if (!value.startsWith("stx:")) return VideoRef("series", value)
        val json = decodeJson(value.removePrefix("stx:"))
        return VideoRef(json.getString("type"), json.getString("id"))
    }

    private fun encodeLanguageRef(ref: VideoRef, language: String): String = "stl:${encodeJson(
        JSONObject().put("type", ref.type).put("id", ref.id).put("language", language)
    )}"

    private fun decodeLanguageRef(value: String): LanguageRef {
        require(value.startsWith("stl:")) { "Invalid add-on stream selection" }
        val json = decodeJson(value.removePrefix("stl:"))
        return LanguageRef(VideoRef(json.getString("type"), json.getString("id")), json.getString("language"))
    }

    private fun encodeJson(json: JSONObject): String = encodeText(json.toString())
    private fun decodeJson(value: String) = JSONObject(decodeText(value))
    private fun encodeText(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decodeText(value: String): String = String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)

    companion object {
        private val SUPPORTED_TYPES = setOf("anime", "series", "movie", "tv")
        private val PROTOCOL_RESOURCES = setOf("catalog", "meta", "stream", "subtitles")
        private val VIDEO_CONTAINERS = setOf("mp4", "mkv", "webm", "m4v", "ts", "mov", "avi")
        private val LANGUAGE_ORDER = listOf("ja", "en", "hi", "mul", "es", "fr", "de", "ar", "ur", "und")
        private const val MAX_CATALOGS = 6
        private const val MAX_TITLES = 100
        private const val MAX_STREAMS = 40
        private const val MANIFEST_TTL_MS = 15 * 60 * 1000L
        private const val STREAM_TTL_MS = 2 * 60 * 1000L
        private const val MANIFEST_TIMEOUT_MS = 15_000
        private const val RESOURCE_TIMEOUT_MS = 15_000
        private const val STREAM_TIMEOUT_MS = 10_000

        fun normalizeManifestUrl(raw: String): String {
            var value = raw.trim()
            if (value.startsWith("stremio://", true)) value = "https://${value.substringAfter("://")}" 
            require(value.startsWith("https://", true) || value.startsWith("http://", true)) {
                "Paste an HTTP, HTTPS, or stremio:// add-on URL"
            }
            value = value.trimEnd('/')
            if (!value.substringBefore('?').endsWith("/manifest.json", true)) value += "/manifest.json"
            return value
        }

        private fun endpoint(manifestUrl: String, resource: String, type: String, id: String, extra: String? = null): String {
            val withoutFragment = manifestUrl.substringBefore('#')
            val query = withoutFragment.substringAfter('?', "")
            val clean = withoutFragment.substringBefore('?').trimEnd('/')
            val base = clean.removeSuffix("/manifest.json")
            return buildString {
                append(base).append('/').append(resource).append('/').append(segment(type)).append('/').append(segment(id))
                if (!extra.isNullOrBlank()) append('/').append(extra)
                append(".json")
                if (query.isNotBlank()) append('?').append(query)
            }
        }

        private fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun qualityHeight(value: String): Int {
            Regex("(?i)(2160|1440|1080|720|576|540|480|360|240)p").find(value)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            return if (Regex("(?i)(^|\\W)4k($|\\W)").containsMatchIn(value)) 2160 else 0
        }

        private fun detectLanguage(value: String): String {
            if (listOf("multi audio", "multi-audio", "dual audio", "dual-audio").any(value::contains)) return "mul"
            return when {
                Regex("(^|\\W)(japanese|jpn|ja)($|\\W)").containsMatchIn(value) -> "ja"
                Regex("(^|\\W)(english|eng|en)($|\\W)").containsMatchIn(value) -> "en"
                Regex("(^|\\W)(hindi|hin)($|\\W)").containsMatchIn(value) -> "hi"
                Regex("(^|\\W)(spanish|spa)($|\\W)").containsMatchIn(value) -> "es"
                Regex("(^|\\W)(french|fra)($|\\W)").containsMatchIn(value) -> "fr"
                Regex("(^|\\W)(german|deu)($|\\W)").containsMatchIn(value) -> "de"
                Regex("(^|\\W)(arabic|ara)($|\\W)").containsMatchIn(value) -> "ar"
                Regex("(^|\\W)(urdu|urd)($|\\W)").containsMatchIn(value) -> "ur"
                else -> "und"
            }
        }

        private fun streamLanguageName(code: String): String = when (code) {
            "mul" -> "Multi audio"
            "und" -> "Original / unspecified"
            else -> languageLabel(code, code.uppercase())
        }
    }

    private data class VideoRef(val type: String, val id: String)
    private data class LanguageRef(val ref: VideoRef, val language: String)
    private data class ExtraSpec(val name: String, val required: Boolean)
    private data class CatalogSpec(val type: String, val id: String, val name: String, val extras: List<ExtraSpec>)
    private data class ResourceSpec(val name: String, val types: Set<String>, val prefixes: List<String>)
    private data class AddonManifest(
        val url: String,
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val types: Set<String>,
        val prefixes: List<String>,
        val resources: List<ResourceSpec>,
        val catalogs: List<CatalogSpec>,
        val p2p: Boolean,
        val configurationRequired: Boolean
    )
    private data class AddonStream(
        val url: String,
        val sourceName: String,
        val sourcePriority: Int,
        val quality: String,
        val height: Int,
        val language: String,
        val bytes: Long,
        val container: String,
        val hls: Boolean,
        val cached: Boolean
    )
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }.filter(String::isNotBlank)
private fun JSONArray?.values(): List<Any> = if (this == null) emptyList() else List(length()) { get(it) }
