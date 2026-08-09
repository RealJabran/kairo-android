package app.kairo.anime.data.source

import app.kairo.anime.data.*

interface AnimeSourceAdapter {
    val kind: SourceKind

    suspend fun browse(source: SourceDefinition): List<Anime>
    suspend fun search(query: String, source: SourceDefinition): List<Anime>
    suspend fun details(anime: Anime, source: SourceDefinition): AnimeDetails
    suspend fun languages(episode: Episode, source: SourceDefinition): List<LanguageOption>
    suspend fun qualities(language: LanguageOption, source: SourceDefinition): List<QualityOption>
}
