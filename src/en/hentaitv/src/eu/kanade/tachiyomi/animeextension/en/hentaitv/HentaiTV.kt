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
import keiyoushi.utils.addSwitchPreference
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
import java.util.Locale

class HentaiTV :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "hentai.tv"
    override val baseUrl = "https://hentai.tv"
    override val lang = "en"
    override val supportsLatest = true

    /** Public guest API – same CDN family as hanime.tv (freeanimehentai.net) */
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
        buildApiRequest(page = page - 1, ordering = "views", orderingDirection = "desc")

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseSearchApiResponse(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        buildApiRequest(page = page - 1, ordering = "created_at_unix", orderingDirection = "desc")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseSearchApiResponse(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sortFilter = filters.firstOrNull { it is SortFilter } as? SortFilter
        val tagFilter = filters.firstOrNull { it is TagFilter } as? TagFilter
        val censoredFilter = filters.firstOrNull { it is CensoredFilter } as? CensoredFilter
        val tagModeFilter = filters.firstOrNull { it is TagModeFilter } as? TagModeFilter

        val ordering = sortFilter?.state?.let { sortableList[it.index].second } ?: "created_at_unix"
        val orderingDirection = if (sortFilter?.state?.ascending == true) "asc" else "desc"

        val includedTags = tagFilter?.state
            ?.filterIsInstance<TagItem>()
            ?.filter { it.isIncluded() }
            ?.map { it.tagId } ?: emptyList()

        val excludedTags = tagFilter?.state
            ?.filterIsInstance<TagItem>()
            ?.filter { it.isExcluded() }
            ?.map { it.tagId } ?: emptyList()

        val tagsMode = if (tagModeFilter != null && tagModeFilter.state == 1) "OR" else "AND"

        // Censored blacklist built from the censored filter selection
        val censoredBlacklist = when (censoredFilter?.state) {
            1 -> listOf("censored")      // uncensored only → blacklist "censored"
            2 -> listOf("uncensored")    // censored only   → blacklist "uncensored"
            else -> emptyList()
        }

        return buildApiRequest(
            page = page - 1,
            ordering = ordering,
            orderingDirection = orderingDirection,
            searchText = query,
            tags = includedTags,
            blacklist = excludedTags + censoredBlacklist,
            tagsMode = tagsMode,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseSearchApiResponse(response)

    // =========================== API Builder ==============================

    private fun buildApiRequest(
        page: Int = 0,
        ordering: String = "created_at_unix",
        orderingDirection: String = "desc",
        searchText: String = "",
        tags: List<String> = emptyList(),
        blacklist: List<String> = emptyList(),
        tagsMode: String = "AND",
        brands: List<String> = emptyList(),
    ): Request {
        val url = "$apiUrl/search_hvs".toHttpUrl().newBuilder().apply {
            addQueryParameter("search_text", searchText)
            if (tags.isEmpty()) {
                addQueryParameter("tags[]", "")
            } else {
                tags.forEach { addQueryParameter("tags[]", it) }
            }
            addQueryParameter("tags_mode", tagsMode)
            if (brands.isEmpty()) {
                addQueryParameter("brands[]", "")
            } else {
                brands.forEach { addQueryParameter("brands[]", it) }
            }
            if (blacklist.isEmpty()) {
                addQueryParameter("blacklist[]", "")
            } else {
                blacklist.forEach { addQueryParameter("blacklist[]", it) }
            }
            addQueryParameter("ordering", ordering)
            addQueryParameter("ordering_direction", orderingDirection)
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", "24")
        }.build()
        return GET(url, apiHeaders())
    }

    // ============================ Data Models ============================

    @Serializable
    data class ApiSearchItem(
        val id: Int = 0,
        val name: String = "",
        val slug: String = "",
        val description: String? = null,
        @SerialName("cover_url") val coverUrl: String? = null,
        @SerialName("poster_url") val posterUrl: String? = null,
        val brand: String? = null,
        @SerialName("brand_id") val brandId: String? = null,
        val tags: List<String> = emptyList(),
        val views: Long = 0L,
        val likes: Long = 0L,
        @SerialName("created_at_unix") val createdAtUnix: Long = 0L,
        @SerialName("released_at_unix") val releasedAtUnix: Long = 0L,
        @SerialName("is_censored") val isCensored: Boolean? = null,
    ) {
        /**
         * The slug for the parent series, e.g. "my-series-3" → "my-series".
         * Single-episode slugs (no trailing number) return themselves.
         */
        val seriesSlug: String
            get() = slug.replace(Regex("-\\d+$"), "").ifBlank { slug }

        /** Episode number extracted from slug suffix, e.g. "my-series-3" → 3. */
        val episodeNumber: Int
            get() = slug.removePrefix(seriesSlug).trimStart('-').toIntOrNull() ?: 1

        /** URL path for the episode on the site, e.g. "/hentai/my-series/ep-1". */
        val episodeUrlPath: String
            get() = "/hentai/$seriesSlug/ep-$episodeNumber"
    }

    @Serializable
    data class HentaiDetailResponse(
        @SerialName("hentai_video") val hentaiVideo: HentaiVideoDetail? = null,
    )

    @Serializable
    data class HentaiVideoDetail(
        val id: Int = 0,
        val name: String = "",
        val slug: String = "",
        val description: String? = null,
        @SerialName("cover_url") val coverUrl: String? = null,
        @SerialName("poster_url") val posterUrl: String? = null,
        val brand: String? = null,
        val tags: List<ApiTag> = emptyList(),
        @SerialName("is_censored") val isCensored: Boolean? = null,
        @SerialName("hentai_episodes") val episodes: List<HentaiEpisode> = emptyList(),
    )

    @Serializable
    data class ApiTag(
        val id: Int = 0,
        val text: String = "",
    )

    @Serializable
    data class HentaiEpisode(
        val id: Int = 0,
        val name: String = "",
        val slug: String = "",
        @SerialName("created_at_unix") val createdAtUnix: Long = 0L,
    )

    @Serializable
    data class EpisodeDetailResponse(
        @SerialName("videos_manifest") val videosManifest: VideosManifest? = null,
        @SerialName("hentai_video") val hentaiVideo: HentaiVideoDetail? = null,
    )

    @Serializable
    data class VideosManifest(
        val servers: List<VideoServer> = emptyList(),
    )

    @Serializable
    data class VideoServer(
        val name: String = "",
        val streams: List<VideoStream> = emptyList(),
    )

    @Serializable
    data class VideoStream(
        val url: String = "",
        val height: Int? = null,
        val width: Int? = null,
        @SerialName("is_guest_allowed") val isGuestAllowed: Boolean? = null,
        @SerialName("is_member_allowed") val isMemberAllowed: Boolean? = null,
        val kind: String? = null,
    )

    // =========================== Browse Parsing ===========================

    private fun parseSearchApiResponse(response: Response): AnimesPage {
        val items = response.parseAs<List<ApiSearchItem>>()

        // Deduplicate by series slug — each series shows once in browse
        val seen = mutableSetOf<String>()
        val animes = items.mapNotNull { v ->
            if (!seen.add(v.seriesSlug)) return@mapNotNull null
            SAnime.create().apply {
                title = v.name.replace(Regex("\\s+\\d+$"), "").trim()
                setUrlWithoutDomain("/series/${v.seriesSlug}")
                thumbnail_url = v.coverUrl?.takeIf { it.isNotBlank() } ?: v.posterUrl
                genre = v.tags.joinToString()
                author = v.brand
                description = v.description
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.trim()
                status = SAnime.UNKNOWN
            }
        }

        return AnimesPage(animes, items.size >= 24)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val seriesSlug = anime.url.removePrefix("/series/")
        // Try the guest API series endpoint first; fallback is handled in parse
        return GET("$apiUrl/hentai/$seriesSlug", apiHeaders())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return try {
            val detail = response.parseAs<HentaiDetailResponse>()
            val hv = requireNotNull(detail.hentaiVideo) { "hentai_video missing" }
            SAnime.create().apply {
                title = hv.name.replace(Regex("\\s+\\d+$"), "").trim()
                thumbnail_url = hv.coverUrl?.takeIf { it.isNotBlank() } ?: hv.posterUrl
                author = hv.brand
                genre = hv.tags.joinToString { it.text }
                description = hv.description?.replace(Regex("<[^>]+>"), "")?.trim()
                status = SAnime.UNKNOWN
            }
        } catch (_: Exception) {
            // Fallback: HTML of the episode-1 page carries metadata in Open Graph + h1
            val seriesSlug = response.request.url.pathSegments.lastOrNull() ?: ""
            val htmlResp = client.newCall(
                GET("$baseUrl/hentai/$seriesSlug/ep-1/", headers),
            ).execute()
            val doc = htmlResp.useAsJsoup()
            SAnime.create().apply {
                title = doc.selectFirst("h1")?.ownText()?.trim()
                    ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                    ?: seriesSlug
                thumbnail_url = doc.selectFirst("meta[property='og:image']")?.attr("content")
                genre = doc.select("a.tag-chip, a[href*='/tag/']").joinToString { it.text() }
                author = doc.selectFirst("a[href*='/brand/'], a[href*='/studio/']")?.text()
                description = doc.selectFirst("meta[property='og:description']")?.attr("content")
                status = SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val seriesSlug = anime.url.removePrefix("/series/")
        return GET("$apiUrl/hentai/$seriesSlug", apiHeaders())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val seriesSlug = response.request.url.pathSegments.lastOrNull() ?: ""

        return try {
            val detail = response.parseAs<HentaiDetailResponse>()
            val hv = requireNotNull(detail.hentaiVideo) { "hentai_video missing" }

            if (hv.episodes.isEmpty()) {
                return singleEpisodeFallback(seriesSlug)
            }

            hv.episodes.mapIndexed { idx, ep ->
                SEpisode.create().apply {
                    setUrlWithoutDomain("/hentai/$seriesSlug/${ep.slug}")
                    name = ep.name.ifBlank { "Episode ${idx + 1}" }
                    episode_number = (idx + 1).toFloat()
                    date_upload = ep.createdAtUnix * 1000L
                }
            }.reversed()
        } catch (_: Exception) {
            // Fallback: search_hvs with the series name to enumerate episodes
            episodeListSearchFallback(seriesSlug)
        }
    }

    private fun episodeListSearchFallback(seriesSlug: String): List<SEpisode> {
        val searchText = seriesSlug.replace('-', ' ')
        val url = "$apiUrl/search_hvs".toHttpUrl().newBuilder().apply {
            addQueryParameter("search_text", searchText)
            addQueryParameter("tags[]", "")
            addQueryParameter("tags_mode", "AND")
            addQueryParameter("brands[]", "")
            addQueryParameter("blacklist[]", "")
            addQueryParameter("ordering", "created_at_unix")
            addQueryParameter("ordering_direction", "asc")
            addQueryParameter("page", "0")
            addQueryParameter("perPage", "100")
        }.build()

        val items = client.newCall(GET(url, apiHeaders())).execute()
            .parseAs<List<ApiSearchItem>>()

        val episodes = items.filter { v ->
            v.seriesSlug == seriesSlug ||
                v.slug == seriesSlug ||
                v.slug.startsWith("$seriesSlug-")
        }.sortedBy { it.episodeNumber }

        if (episodes.isEmpty()) return singleEpisodeFallback(seriesSlug)

        return episodes.map { v ->
            SEpisode.create().apply {
                setUrlWithoutDomain(v.episodeUrlPath)
                name = v.name.replace(Regex("\\s+\\d+$"), "").trim().let { base ->
                    "$base ${v.episodeNumber}".trim()
                }.ifBlank { "Episode ${v.episodeNumber}" }
                episode_number = v.episodeNumber.toFloat()
                date_upload = v.createdAtUnix * 1000L
            }
        }.reversed()
    }

    private fun singleEpisodeFallback(seriesSlug: String) = listOf(
        SEpisode.create().apply {
            setUrlWithoutDomain("/hentai/$seriesSlug/ep-1")
            name = "Episode 1"
            episode_number = 1f
        },
    )

    // ============================== Video List ============================

    override fun videoListRequest(episode: SEpisode): Request {
        // episode.url is e.g. "/hentai/discipline/ep-1"
        // Guest API endpoint: GET $apiUrl/hentai/discipline/ep-1
        return GET("$apiUrl${episode.url}", apiHeaders())
    }

    override fun videoListParse(response: Response): List<Video> {
        return try {
            val epData = response.parseAs<EpisodeDetailResponse>()
            val servers = epData.videosManifest?.servers
            require(!servers.isNullOrEmpty()) { "No videos_manifest in API response" }

            val includePremium = preferences.getBoolean(PREF_PREMIUM_KEY, PREF_PREMIUM_DEFAULT)
            val videoHeaders = headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .set("Origin", baseUrl)
                .build()

            servers.parallelCatchingFlatMapBlocking { server ->
                val usableStreams = server.streams.filter { stream ->
                    stream.url.contains(".m3u8") &&
                        stream.kind != "premium_alert" &&
                        (
                            stream.isGuestAllowed == true ||
                                (includePremium && stream.isMemberAllowed == true)
                            )
                }

                usableStreams.flatMap { stream ->
                    runCatching {
                        playlistUtils.extractFromHls(
                            playlistUrl = stream.url,
                            masterHeaders = videoHeaders,
                            videoHeaders = videoHeaders,
                            videoNameGen = { quality ->
                                val label = if (quality == "Video") {
                                    "${stream.height ?: "?"}p"
                                } else {
                                    quality
                                }
                                "${server.name} - $label"
                            },
                        )
                    }.getOrElse {
                        listOf(
                            Video(
                                stream.url,
                                "${server.name} - ${stream.height ?: "?"}p",
                                stream.url,
                                headers = videoHeaders,
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback: load the HTML episode page and extract from JSON-LD / __NEXT_DATA__
            videoListHtmlFallback(response.request.url.encodedPath)
        }
    }

    /**
     * Fallback video extraction from the HTML episode page.
     * Tries (in order):
     *  1. `script#__NEXT_DATA__` — Next.js hydration payload (most reliable on Next.js sites)
     *  2. `application/ld+json` embedUrl → nhplayer.com embed URL
     */
    private fun videoListHtmlFallback(apiPath: String): List<Video> {
        // apiPath = "/api/v11/hentai/discipline/ep-1" → strip prefix → "/hentai/discipline/ep-1"
        val sitePath = apiPath
            .removePrefix("/api/v11")
            .trimEnd('/')
        val pageUrl = "$baseUrl$sitePath/"

        val doc = client.newCall(GET(pageUrl, headers)).execute().useAsJsoup()
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        // --- Attempt 1: __NEXT_DATA__ ---
        val nextDataScript = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextDataScript.isNullOrBlank()) {
            val m3u8s = M3U8_REGEX.findAll(nextDataScript)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            if (m3u8s.isNotEmpty()) {
                return m3u8s.flatMap { m3u8 ->
                    runCatching {
                        playlistUtils.extractFromHls(
                            playlistUrl = m3u8,
                            masterHeaders = videoHeaders,
                            videoHeaders = videoHeaders,
                            videoNameGen = { it },
                        )
                    }.getOrDefault(emptyList())
                }
            }
        }

        // --- Attempt 2: JSON-LD embedUrl → nhplayer ---
        val ldJson = doc.selectFirst("script[type='application/ld+json']")?.data() ?: ""
        val embedUrls = JSONLD_EMBED_REGEX.findAll(ldJson)
            .map { it.groupValues[1] }
            .filter { it.contains("nhplayer") }
            .distinct()
            .toList()

        if (embedUrls.isEmpty()) {
            // Last resort: search all iframes for nhplayer
            doc.select("iframe[src*='nhplayer']")
                .map { it.attr("abs:src") }
                .filter { it.isNotBlank() }
                .also { if (it.isEmpty()) return emptyList() }
                .let { return extractFromNhPlayerList(it, videoHeaders) }
        }

        return extractFromNhPlayerList(embedUrls, videoHeaders)
    }

    private fun extractFromNhPlayerList(urls: List<String>, videoHeaders: Headers): List<Video> {
        val nhpReferer = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
        val hlsHeaders = headers.newBuilder()
            .set("Referer", "https://nhplayer.com/")
            .set("Origin", "https://nhplayer.com")
            .build()

        return urls.parallelCatchingFlatMapBlocking { playerUrl ->
            val body = client.newCall(GET(playerUrl, nhpReferer)).execute().body.string()
            M3U8_REGEX.findAll(body)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
                .flatMap { m3u8 ->
                    runCatching {
                        playlistUtils.extractFromHls(
                            playlistUrl = m3u8,
                            masterHeaders = hlsHeaders,
                            videoHeaders = hlsHeaders,
                            videoNameGen = { "NHPlayer - $it" },
                        )
                    }.getOrDefault(emptyList())
                }
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        CensoredFilter(),
        AnimeFilter.Separator(),
        TagModeFilter(),
        TagFilter(),
    )

    private val sortableList = listOf(
        Pair("Latest Upload", "created_at_unix"),
        Pair("Most Viewed", "views"),
        Pair("Top Rated", "likes"),
        Pair("Release Date", "released_at_unix"),
    )

    class SortFilter : AnimeFilter.Sort(
        "Sort By",
        arrayOf("Latest Upload", "Most Viewed", "Top Rated", "Release Date"),
        Selection(0, false),
    )

    class CensoredFilter : AnimeFilter.Select<String>(
        "Censored Content",
        arrayOf("All", "Uncensored Only", "Censored Only"),
    )

    class TagModeFilter : AnimeFilter.Select<String>(
        "Tag filter mode",
        arrayOf("AND (all selected tags must match)", "OR (any selected tag matches)"),
    )

    /** TriState tag — supports include (✓), exclude (✗), and ignore. */
    class TagItem(val tagId: String, name: String) : AnimeFilter.TriState(name)

    class TagFilter : AnimeFilter.Group<TagItem>(
        "Tags / Genres",
        ALL_TAGS.map { (id, displayName) -> TagItem(id, displayName) },
    )

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = QUALITY_LIST.toList(),
            entryValues = QUALITY_LIST.toList(),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_PREMIUM_KEY,
            title = "Include member-only streams",
            summary = "Show streams that require a premium account. " +
                "They will fail to play without a logged-in session cookie.",
            default = PREF_PREMIUM_DEFAULT,
        )
    }

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> {
                // Put preferred quality first
                QUALITY_RESOLUTION_REGEX.find(it.quality)?.groupValues?.get(1)
                    ?.toIntOrNull()
                    ?.let { q -> if (it.quality.contains(preferred, ignoreCase = true)) q + 10000 else q }
                    ?: 0
            },
        )
    }

    // ============================== Companions ===========================

    companion object {

        private val JSONLD_EMBED_REGEX = Regex(""""embedUrl"\s*:\s*"([^"]+)"""")
        private val M3U8_REGEX = Regex("""["'](https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)["']""")
        private val QUALITY_RESOLUTION_REGEX = Regex("""(\d+)p""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_PREMIUM_KEY = "include_premium_streams"
        private const val PREF_PREMIUM_DEFAULT = false

        /**
         * Tag list — IDs must match exactly what the guest API search_hvs `tags[]` parameter expects
         * (case-sensitive lowercase strings as returned in the `tags` array of search results).
         */
        val ALL_TAGS = listOf(
            Pair("3d", "3D"),
            Pair("ahegao", "Ahegao"),
            Pair("anal", "Anal"),
            Pair("bdsm", "BDSM"),
            Pair("big boobs", "Big Boobs"),
            Pair("blow job", "Blow Job"),
            Pair("bondage", "Bondage"),
            Pair("boob job", "Boob Job"),
            Pair("censored", "Censored"),
            Pair("cheating", "Cheating"),
            Pair("comedy", "Comedy"),
            Pair("cosplay", "Cosplay"),
            Pair("creampie", "Creampie"),
            Pair("dark skin", "Dark Skin"),
            Pair("facial", "Facial"),
            Pair("fantasy", "Fantasy"),
            Pair("filmed", "Filmed"),
            Pair("foot job", "Foot Job"),
            Pair("futanari", "Futanari"),
            Pair("gangbang", "Gangbang"),
            Pair("glasses", "Glasses"),
            Pair("group", "Group"),
            Pair("hand job", "Hand Job"),
            Pair("harem", "Harem"),
            Pair("horror", "Horror"),
            Pair("housewife", "Housewife"),
            Pair("incest", "Incest"),
            Pair("inflation", "Inflation"),
            Pair("lactation", "Lactation"),
            Pair("maid", "Maid"),
            Pair("masturbation", "Masturbation"),
            Pair("milf", "MILF"),
            Pair("mind break", "Mind Break"),
            Pair("mind control", "Mind Control"),
            Pair("monster", "Monster"),
            Pair("netorare", "Netorare / NTR"),
            Pair("nurse", "Nurse"),
            Pair("orgy", "Orgy"),
            Pair("pov", "POV"),
            Pair("pregnant", "Pregnant"),
            Pair("public sex", "Public Sex"),
            Pair("rape", "Rape"),
            Pair("reverse rape", "Reverse Rape"),
            Pair("school girl", "School Girl"),
            Pair("shota", "Shota"),
            Pair("swimsuit", "Swimsuit"),
            Pair("teacher", "Teacher"),
            Pair("tentacle", "Tentacle"),
            Pair("threesome", "Threesome"),
            Pair("toys", "Toys"),
            Pair("trap", "Trap"),
            Pair("ugly bastard", "Ugly Bastard"),
            Pair("uncensored", "Uncensored"),
            Pair("virgin", "Virgin"),
            Pair("vanilla", "Vanilla"),
            Pair("x-ray", "X-Ray"),
            Pair("yaoi", "Yaoi"),
            Pair("yuri", "Yuri"),
        )
    }
}
