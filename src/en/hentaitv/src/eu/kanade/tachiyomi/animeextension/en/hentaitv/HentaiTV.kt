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
import keiyoushi.utils.useAsJsoup
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

    private val preferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=views&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseCardPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=recently_added&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseCardPage(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$baseUrl/browse".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("page", page.toString())
        if (query.isNotBlank()) urlBuilder.addQueryParameter("q", query)
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("sort", filter.toUriPart())
                is GenreFilter -> filter.getChecked().forEach { urlBuilder.addQueryParameter("genre[]", it) }
                else -> {}
            }
        }
        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseCardPage(response)

    // =========================== Card Parsing ============================

    /**
     * Parse a browse/search/trending page with episode card grid.
     * Cards are `<a href="/hentai/{slug}"><div class="card card--below">…</div></a>`.
     * We group to series level by stripping the "-episode-N" suffix from the slug,
     * deduplicating so each series appears only once.
     */
    private fun parseCardPage(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val seen = mutableSetOf<String>()
        val animes = document.select("a:has(div.card)").mapNotNull { el ->
            val href = el.attr("href").takeIf { it.contains("/hentai/") } ?: return@mapNotNull null
            val seriesUrl = deriveSeriesUrl(href)
            if (!seen.add(seriesUrl)) return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(seriesUrl)
                // ownText() on card-title strips the nested "· EP N" span
                title = el.selectFirst("div.card-title")?.ownText()?.trim()
                    ?.ifBlank {
                        el.selectFirst("div.card-title")?.text()
                            ?.replace(Regex("\\s*·\\s*EP\\s*\\d+.*$"), "")?.trim()
                    } ?: ""
                val rawSrc = el.selectFirst("img.poster-img")?.attr("src") ?: ""
                thumbnail_url = when {
                    rawSrc.startsWith("http") -> rawSrc
                    rawSrc.isNotBlank() -> "$baseUrl$rawSrc"
                    else -> null
                }
            }
        }
        // hentai.tv doesn't expose a "page N of M" counter in HTML; we infer
        // hasNextPage from whether a full page of cards was returned.
        val hasNextPage = document.select("a:has(div.card)").size >= 20
        return AnimesPage(animes, hasNextPage)
    }

    /**
     * Derive the series URL from an episode URL.
     * /hentai/my-title-episode-3  →  /series/my-title
     * /hentai/my-one-shot         →  /series/my-one-shot
     */
    private fun deriveSeriesUrl(episodeHref: String): String {
        val slug = episodeHref.substringAfterLast("/hentai/").trimEnd('/')
        val seriesSlug = slug.replace(Regex("-episode-\\d+$"), "")
        return "/series/$seriesSlug"
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.series-title, h1.watch-title, h1")?.text()?.trim() ?: ""
            description = document.selectFirst(
                "div.series-desc, p.series-desc, div.watch-desc, p.description, div.description",
            )?.text()
            genre = document.select(
                "span.tag-chip, div.tag-chip, a.tag-chip, " +
                    "span.footer-tag, a.footer-tag",
            ).joinToString { it.text() }
            val imgEl = document.selectFirst(
                "img.series-cover, img.cover-img, img.poster-img, " +
                    "div.series-poster img, div.watch-cover img",
            )
            thumbnail_url = imgEl?.attr("src")?.let { src ->
                if (src.startsWith("http")) src else "$baseUrl$src"
            }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    /**
     * Parse episode list from the series page at /series/{slug}.
     * Episodes are expected to appear as card links (`<a href="/hentai/…">`).
     * Falls back to a single synthesised episode when the page has no episode links
     * (handles one-shots whose slug has no "-episode-N" suffix).
     */
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()

        val episodeEls = document.select("a:has(div.card)[href*='/hentai/'], a[href*='/hentai/']")
            .distinctBy { it.attr("href") }
            .filter { el -> el.attr("href").contains("/hentai/") }

        if (episodeEls.isNotEmpty()) {
            return episodeEls.mapIndexed { index, el ->
                val href = el.attr("href").let { if (it.startsWith("/")) it else "/$it" }
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    val epText = el.selectFirst("span.badge, span.card-ep, div.card-ep")?.text() ?: ""
                    val epNum = epText.filter { c -> c.isDigit() }.toFloatOrNull()
                        ?: (episodeEls.size - index).toFloat()
                    name = el.selectFirst("div.card-title")?.ownText()?.trim()
                        ?.ifBlank { "Episode ${epNum.toInt()}" }
                        ?: "Episode ${epNum.toInt()}"
                    episode_number = epNum
                }
            }.sortedByDescending { it.episode_number }
        }

        // Fallback: synthesise a single episode pointing back to the episode page
        val seriesSlug = response.request.url.pathSegments.last()
        val episodeSlug = "$seriesSlug-episode-1"
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain("/hentai/$episodeSlug")
                name = document.selectFirst("h1.series-title, h1")?.text() ?: "Episode 1"
                episode_number = 1f
            },
        )
    }

    // ============================== Video List ============================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()

        // Primary: nhplayer.com iframes embedded directly in the page
        val iframeUrls = document.select("iframe[src*='nhplayer.com']")
            .map { it.attr("src") }
            .filter { it.isNotBlank() }
            .distinct()

        // Fallback: embedUrl inside JSON-LD or RSC data scripts
        val scriptUrls = if (iframeUrls.isEmpty()) {
            val scriptData = document.select("script").joinToString(" ") { it.data() }
            NHP_EMBED_REGEX.findAll(scriptData).map { it.groupValues[1] }.toList()
        } else {
            emptyList()
        }

        val allPlayerUrls = (iframeUrls + scriptUrls).distinct()

        require(allPlayerUrls.isNotEmpty()) {
            "No video sources found on this page. The site may require login for this content."
        }

        val videos = allPlayerUrls.parallelCatchingFlatMapBlocking { playerUrl ->
            extractFromNhPlayer(playerUrl)
        }

        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return videos.sortedWith(compareBy { !it.quality.contains(preferred, ignoreCase = true) })
    }

    /**
     * Fetch an nhplayer.com embed page and extract HLS stream URLs.
     * The player embeds a video source (m3u8) in its page scripts.
     */
    private fun extractFromNhPlayer(playerUrl: String): List<Video> {
        val playerPageHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        val body = client.newCall(GET(playerUrl, playerPageHeaders)).execute()
            .body.string()

        val m3u8Urls = M3U8_URL_REGEX.findAll(body)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val videoHeaders = headers.newBuilder()
            .set("Referer", "https://nhplayer.com/")
            .set("Origin", "https://nhplayer.com")
            .build()

        return m3u8Urls.flatMap { m3u8Url ->
            runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = m3u8Url,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { quality -> "NHPlayer - $quality" },
                )
            }.getOrDefault(emptyList())
        }
    }

    // ============================== Filters ===============================

    private class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Default", ""),
            Pair("Most Viewed", "views"),
            Pair("Recently Added", "recently_added"),
            Pair("Top Rated", "top"),
            Pair("Trending", "trending"),
        ),
    )

    private class GenreFilter : CheckBoxFilterList(
        "Genres",
        arrayOf(
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("BDSM", "bdsm"),
            Pair("Big Boobs", "big-boobs"),
            Pair("Censored", "censored"),
            Pair("Cheating", "cheating"),
            Pair("Comedy", "comedy"),
            Pair("Creampie", "creampie"),
            Pair("Dark Skin", "dark-skin"),
            Pair("Fantasy", "fantasy"),
            Pair("Gender Bender", "gender-bender"),
            Pair("Group", "group"),
            Pair("Harem", "harem"),
            Pair("Horror", "horror"),
            Pair("Housewife", "housewife"),
            Pair("Incest", "incest"),
            Pair("Lactation", "lactation"),
            Pair("Masturbation", "masturbation"),
            Pair("Milf", "milf"),
            Pair("Mind Break", "mind-break"),
            Pair("Monster", "monster"),
            Pair("Netorare", "netorare"),
            Pair("Nurse", "nurse"),
            Pair("Outdoor", "outdoor"),
            Pair("School Girl", "school-girl"),
            Pair("Shotacon", "shotacon"),
            Pair("Succubus", "succubus"),
            Pair("Tentacle", "tentacle"),
            Pair("Threesome", "threesome"),
            Pair("Toys", "toys"),
            Pair("Uncensored", "uncensored"),
            Pair("Virgin", "virgin"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
    )

    // ========================== Filter helpers ============================

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
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
        /** Matches nhplayer.com embed URLs in JSON-LD / RSC script data. */
        private val NHP_EMBED_REGEX =
            Regex(""""embedUrl"\s*:\s*"(https://nhplayer\.com/v/[^"]+)"""")

        /** Matches m3u8 stream URLs inside nhplayer page scripts. */
        private val M3U8_URL_REGEX =
            Regex("""["'](https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)["']""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }
}