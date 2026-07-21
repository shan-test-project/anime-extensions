package eu.kanade.tachiyomi.animeextension.en.hentaitv

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class HentaiTV :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "hentai.tv"
    override val baseUrl = "https://hentai.tv"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://guest.freeanimehentai.net/api/v11"
    private val preferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        )

    private fun apiHeaders(): Headers = headers.newBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        buildApiRequest(page = page - 1, orderBy = "views")

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseApiResponse(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        buildApiRequest(page = page - 1, orderBy = "created_at_unix")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseApiResponse(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sortFilter = filters.firstOrNull { it is SortFilter } as? SortFilter
        val tagFilter = filters.firstOrNull { it is TagFilter } as? TagFilter
        val orderBy = sortFilter?.toUriPart() ?: "created_at_unix"
        val tags = tagFilter?.getChecked() ?: emptyList()
        return buildApiRequest(page = page - 1, orderBy = orderBy, searchText = query, tags = tags)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseApiResponse(response)

    // =========================== API Builder ==============================

    private fun buildApiRequest(
        page: Int = 0,
        orderBy: String = "created_at_unix",
        searchText: String = "",
        tags: List<String> = emptyList(),
    ): Request {
        val url = "$apiUrl/search_hvs".toHttpUrl().newBuilder().apply {
            addQueryParameter("search_text", searchText)
            if (tags.isEmpty()) {
                addQueryParameter("tags[]", "")
            } else {
                tags.forEach { addQueryParameter("tags[]", it) }
            }
            addQueryParameter("tags_mode", "AND")
            addQueryParameter("brands[]", "")
            addQueryParameter("blacklist[]", "")
            addQueryParameter("order_by", orderBy)
            addQueryParameter("ordering", "desc")
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, apiHeaders())
    }

    // =========================== Data Models ==============================

    @Serializable
    data class ApiVideo(
        val id: Int = 0,
        val name: String = "",
        val slug: String = "",
        val description: String? = null,
        @SerialName("cover_url") val coverUrl: String? = null,
        @SerialName("poster_url") val posterUrl: String? = null,
        val brand: String? = null,
        val tags: List<String> = emptyList(),
        val views: Long = 0L,
        @SerialName("created_at_unix") val createdAtUnix: Long = 0L,
    ) {
        /** The slug with the trailing episode number stripped, e.g. "my-series-1" → "my-series" */
        val baseSlug: String get() = slug.replace(Regex("-\\d+$"), "")

        /** The episode number extracted from the slug end, e.g. "my-series-3" → 3 */
        val episodeNumber: Int get() = slug.removePrefix(baseSlug).trimStart('-').toIntOrNull() ?: 1

        /** Convert API slug to the hentai.tv episode URL path.
         *  "my-series-2" → "/hentai/my-series-episode-2" */
        val episodeUrlPath: String
            get() = "/hentai/${baseSlug}-episode-${episodeNumber}"
    }

    // =========================== API Parsing ==============================

    private fun parseApiResponse(response: Response): AnimesPage {
        val videos = response.parseAs<List<ApiVideo>>()

        // Deduplicate by base slug so each series appears once in browse
        val seen = mutableSetOf<String>()
        val animes = videos.mapNotNull { v ->
            if (!seen.add(v.baseSlug)) return@mapNotNull null
            SAnime.create().apply {
                // Display title: strip trailing episode number digit
                title = v.name.replace(Regex("\\s+\\d+$"), "").trim()
                // Store base slug as URL so we can find all episodes later
                setUrlWithoutDomain("/api/${v.baseSlug}")
                thumbnail_url = v.coverUrl?.takeIf { it.isNotBlank() } ?: v.posterUrl
                genre = v.tags.joinToString()
                author = v.brand
                description = v.description
                    ?.replace(Regex("<[^>]+>"), "")  // strip HTML tags
                    ?.trim()
            }
        }

        // API returns 24 items per page; fewer → last page
        val hasNextPage = videos.size >= 24
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val baseSlug = anime.url.removePrefix("/api/")
        // Fetch the first episode page — it holds JSON-LD with full series metadata
        return GET("$baseUrl/hentai/${baseSlug}-episode-1", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        val ldJson = document.selectFirst("script[type='application/ld+json']")?.data() ?: ""

        return SAnime.create().apply {
            // Title from <h1 class="watch-title"> (cleaner than JSON-LD which has site name appended)
            val h1 = document.selectFirst("h1.watch-title")
            title = if (h1 != null) {
                // Remove episode span: "My Series <span>Episode 2</span>" → "My Series"
                h1.ownText().trim().ifBlank {
                    h1.text().replace(Regex("\\s*Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "").trim()
                }
            } else {
                JSONLD_NAME_REGEX.find(ldJson)?.groupValues?.get(1)
                    ?.replace(Regex("\\s+Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
                    ?.replace(Regex("\\s*-\\s*Watch on.*", RegexOption.IGNORE_CASE), "")
                    ?.trim() ?: ""
            }

            // Description from JSON-LD (full and clean)
            description = JSONLD_DESC_REGEX.find(ldJson)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("\\u003c", "<")
                ?.replace("\\u003e", ">")
                ?.replace(Regex("\\\\[rn]"), "\n")
                ?.trim()

            // Thumbnail from JSON-LD thumbnailUrl array
            thumbnail_url = JSONLD_THUMB_REGEX.find(ldJson)?.groupValues?.get(1)

            // Genres from tag-chip links on the page
            genre = document.select("a.tag-chip").joinToString { it.text() }

            // Studio / brand
            author = document.selectFirst("a[href^='/brand/'], a[href^='/studio/']")?.text()

            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val baseSlug = anime.url.removePrefix("/api/")
        // Convert base slug to search text: "my-series-name" → "my series name"
        val searchText = baseSlug.replace('-', ' ')
        val url = "$apiUrl/search_hvs".toHttpUrl().newBuilder().apply {
            addQueryParameter("search_text", searchText)
            addQueryParameter("tags[]", "")
            addQueryParameter("tags_mode", "AND")
            addQueryParameter("brands[]", "")
            addQueryParameter("blacklist[]", "")
            addQueryParameter("order_by", "created_at_unix")
            addQueryParameter("ordering", "asc")
            addQueryParameter("page", "0")
        }.build()
        return GET(url, apiHeaders())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        // Recover base slug from the search_text query parameter we put in the request URL
        val baseSlug = response.request.url.queryParameter("search_text")
            ?.replace(' ', '-') ?: ""

        val allVideos = response.parseAs<List<ApiVideo>>()

        // Keep only episodes whose base slug exactly matches
        val episodes = allVideos.filter { v ->
            v.baseSlug == baseSlug ||
                v.slug == baseSlug ||        // slug IS the base slug (single episode, no number)
                v.slug.startsWith("$baseSlug-")
        }.sortedBy { it.episodeNumber }

        if (episodes.isEmpty()) {
            // Fallback: create a synthetic single episode
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain("/hentai/${baseSlug}-episode-1")
                    name = "Episode 1"
                    episode_number = 1f
                },
            )
        }

        return episodes.map { v ->
            SEpisode.create().apply {
                setUrlWithoutDomain(v.episodeUrlPath)
                name = v.name
                episode_number = v.episodeNumber.toFloat()
                date_upload = v.createdAtUnix * 1000L
            }
        }.reversed() // Newest first
    }

    // ============================== Video List ============================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val ldJson = document.selectFirst("script[type='application/ld+json']")?.data() ?: ""

        // Collect all nhplayer embed URLs — from JSON-LD first, then iframes as fallback
        val embedUrls = JSONLD_EMBED_REGEX.findAll(ldJson)
            .map { it.groupValues[1] }
            .filter { it.contains("nhplayer.com") }
            .toMutableList()

        if (embedUrls.isEmpty()) {
            document.select("iframe[src*='nhplayer.com']")
                .mapTo(embedUrls) { it.attr("abs:src") }
        }

        require(embedUrls.isNotEmpty()) {
            "No video player found on this page. The episode may require a premium account."
        }

        return embedUrls.distinct().parallelCatchingFlatMapBlocking { playerUrl ->
            extractFromNhPlayer(playerUrl)
        }
    }

    /**
     * Fetch nhplayer.com embed page and extract HLS m3u8 URLs from its inline scripts.
     */
    private fun extractFromNhPlayer(playerUrl: String): List<Video> {
        val nhpHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        val body = client.newCall(GET(playerUrl, nhpHeaders)).execute().body.string()

        val m3u8Urls = M3U8_REGEX.findAll(body)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val hlsHeaders = headers.newBuilder()
            .set("Referer", "https://nhplayer.com/")
            .set("Origin", "https://nhplayer.com")
            .build()

        return m3u8Urls.flatMap { m3u8 ->
            runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = m3u8,
                    masterHeaders = hlsHeaders,
                    videoHeaders = hlsHeaders,
                    videoNameGen = { quality -> "NHPlayer - $quality" },
                )
            }.getOrDefault(emptyList())
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        TagFilter(),
    )

    private class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Latest", "created_at_unix"),
            Pair("Most Viewed", "views"),
            Pair("Top Rated", "likes"),
        ),
    )

    private class TagFilter : CheckBoxFilterList(
        "Tags / Genres",
        arrayOf(
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("BDSM", "bdsm"),
            Pair("Big Boobs", "big boobs"),
            Pair("Blowjob", "blow job"),
            Pair("Censored", "censored"),
            Pair("Cheating", "cheating"),
            Pair("Creampie", "creampie"),
            Pair("Dark Skin", "dark skin"),
            Pair("Fantasy", "fantasy"),
            Pair("Group", "group"),
            Pair("Harem", "harem"),
            Pair("Horror", "horror"),
            Pair("Housewife", "housewife"),
            Pair("Incest", "incest"),
            Pair("Mind Break", "mind break"),
            Pair("Monster", "monster"),
            Pair("Netorare", "ntr"),
            Pair("Nurse", "nurse"),
            Pair("School Girl", "school girl"),
            Pair("Tentacle", "tentacle"),
            Pair("Uncensored", "uncensored"),
            Pair("Virgin", "virgin"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class CheckBoxVal(name: String) : AnimeFilter.CheckBox(name, false)

    private open class CheckBoxFilterList(
        name: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, vals.map { CheckBoxVal(it.first) }) {
        fun getChecked(): List<String> =
            state.mapIndexedNotNull { i, cb -> if (cb.state) vals[i].second else null }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080p", "720p", "480p", "360p"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareBy { !it.quality.contains(preferred, ignoreCase = true) })
    }

    // =============================== Regex ================================

    companion object {
        /** Extracts embedUrl from JSON-LD (nhplayer embed URL) */
        private val JSONLD_EMBED_REGEX =
            Regex(""""embedUrl"\s*:\s*"([^"]+)"""")

        /** First "name" in JSON-LD = VideoObject name */
        private val JSONLD_NAME_REGEX =
            Regex(""""name"\s*:\s*"([^"]+)"""")

        /** Description in JSON-LD */
        private val JSONLD_DESC_REGEX =
            Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        /** First thumbnail URL from thumbnailUrl array in JSON-LD */
        private val JSONLD_THUMB_REGEX =
            Regex(""""thumbnailUrl"\s*:\s*\[\s*"([^"]+)"""")

        /** m3u8 stream URL inside nhplayer page scripts */
        private val M3U8_REGEX =
            Regex("""["'](https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)["']""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }
}
