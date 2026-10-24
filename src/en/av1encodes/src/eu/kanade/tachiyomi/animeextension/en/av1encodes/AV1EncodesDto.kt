package eu.kanade.tachiyomi.animeextension.en.av1encodes

import kotlinx.serialization.Serializable

/** One episode file as listed on an /episodes/<slug>/<season>/<res> page. */
data class EpisodeEntry(
    val season: String,
    val number: Float,
    val label: String,
    val audio: String?,
    /** Site-relative /download/... path including the ?token= query. */
    val href: String,
    val fileName: String,
    /** Raw /get_ddl/ style object when the listing embeds it (JSON listings only). */
    val ddl: String?,
)

/** State carried from the hoster list to the lazy video resolution. */
@Serializable
data class HosterData(
    val href: String,
    val referer: String,
    val resolution: Int,
    val audio: String? = null,
    val ddl: String? = null,
)

/** Normalised /get_ddl/ answer. All links are absolute. */
data class DdlLinks(
    val stream: String?,
    val download: String?,
    val watch: String?,
    val dash: String?,
    val torrent: String?,
    val fileName: String?,
    val fileSize: String?,
    val audioLanguages: List<String>,
    val subtitles: List<Pair<String, String>>,
)
