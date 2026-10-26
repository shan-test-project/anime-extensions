package eu.kanade.tachiyomi.animeextension.en.pornhub

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class Pornhub : Source() {

    override val name = "Pornhub"

    override val lang = "en"

    override val supportsLatest = true

    override val baseUrl = "https://www.pornhub.com"

    /** Age gate and desktop layout cookies, so every page renders the same markup the selectors expect. */
    override val client: OkHttpClient = network.client.newBuilder()
        .addCookie(
            listOf(
                "accessAgeDisclaimerPH" to "1",
                "accessAgeDisclaimerUK" to "1",
                "accessPH" to "1",
                "age_verified" to "1",
                "platform" to "pc",
            ),
        )
        .dns(DohDns(network.client) { preferences.getBoolean(PREF_DOH_KEY, PREF_DOH_DEFAULT) })
        .build()

    /** Local parallel downloader / HLS prefetcher the player streams through. */
    private val proxy by lazy { StreamProxy(client) }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val pageHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.9")
            .build()
    }

    private val ajaxHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    /** The CDN answers 404/410 without the browser User-Agent and site Referer. */
    private val videoHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .set("Accept", "*/*")
            .build()
    }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val prefServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val prefQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val prefPopularSort by preferences.delegate(PREF_POPULAR_KEY, PREF_POPULAR_DEFAULT)
    private val prefPeriod by preferences.delegate(PREF_PERIOD_KEY, PREF_PERIOD_DEFAULT)
    private val prefBuffer by preferences.delegate(PREF_BUFFER_KEY, PREF_BUFFER_DEFAULT)
    private val prefAccelerator by preferences.delegate(PREF_ACCEL_KEY, PREF_ACCEL_DEFAULT)
    private val prefLatestMin by preferences.delegate(PREF_LATEST_MIN_KEY, PREF_LATEST_MIN_DEFAULT)

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/video".toHttpUrl().newBuilder()
            .apply {
                if (prefPopularSort != "fr") addQueryParameter("o", prefPopularSort)
                if (prefPopularSort in PERIOD_SORTS) addQueryParameter("t", prefPeriod)
            }
            .addPage(page)
            .build()
        return videoListing(url)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = "$baseUrl/video".toHttpUrl().newBuilder()
            .addQueryParameter("o", "cm")
            .apply { if (prefLatestMin.isNotEmpty()) addQueryParameter("min_duration", prefLatestMin) }
            .addPage(page)
            .build()
        return videoListing(url)
    }

    // =============================== Search ===============================

    @Volatile private var pornstars: List<Pair<String, String>> = loadCachedPornstars()

    @Volatile private var pornstarJob: Job? = null

    /** Shows the cached pornstar list and refreshes it from the site in the background (tap Reset to see it). */
    override fun getFilterList(): AnimeFilterList {
        val age = System.currentTimeMillis() - preferences.getLong(PREF_PORNSTARS_TIME_KEY, 0L)
        if ((pornstars.isEmpty() || age > PORNSTAR_TTL_MS) && pornstarJob?.isActive != true) {
            pornstarJob = backgroundScope.launch { runCatching { fetchPornstars() } }
        }
        return PornhubFilters.getFilterList(pornstars)
    }

    private suspend fun fetchPornstars() = coroutineScope {
        val pages = (1..PORNSTAR_PAGES).map { page ->
            async {
                runCatching {
                    val url = "$baseUrl/pornstars?performerType=pornstar" + if (page > 1) "&page=$page" else ""
                    client.get(url, pageHeaders).useAsJsoup()
                        .select("li.pornstarLi a[href^=/pornstar/] img[alt]")
                        .mapNotNull { img ->
                            val slug = img.parent()?.attr("href")?.substringAfter("/pornstar/")?.substringBefore('/')?.substringBefore('?')
                            val name = img.attr("alt").trim()
                            if (slug.isNullOrBlank() || name.isBlank()) null else name to slug
                        }
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        val list = pages.flatten().distinctBy { it.second }
        if (list.isNotEmpty()) {
            pornstars = list
            preferences.edit()
                .putString(PREF_PORNSTARS_KEY, list.joinToString("\n") { "${it.second}\t${it.first}" })
                .putLong(PREF_PORNSTARS_TIME_KEY, System.currentTimeMillis())
                .apply()
        }
    }

    private fun loadCachedPornstars(): List<Pair<String, String>> = preferences.getString(PREF_PORNSTARS_KEY, null)
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val slug = line.substringBefore('\t')
            val name = line.substringAfter('\t', "")
            if (slug.isBlank() || name.isBlank()) null else name to slug
        }
        .toList()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val trimmed = query.trim()
        linkTarget(trimmed)?.let { return openLink(it, page) }

        val params = PornhubFilters.parse(filters)
        val starName = pornstars.firstOrNull { it.second == params.pornstar }?.first
        val url = when {
            params.pornstar.isNotEmpty() && trimmed.isEmpty() -> pornstarUrl(params, page)
            params.pornstar.isNotEmpty() -> searchUrl("$trimmed ${starName ?: params.pornstar.replace('-', ' ')}", params, page)
            trimmed.isNotEmpty() -> searchUrl(trimmed, params, page)
            else -> browseUrl(params, page)
        }
        return videoListing(url)
    }

    private fun searchUrl(query: String, params: PornhubFilters.Params, page: Int): HttpUrl {
        val path = if (params.orientation == "gay") "/gay/video/search" else "/video/search"
        return "$baseUrl$path".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .apply {
                when (params.sort) {
                    "" -> {}
                    "new" -> addQueryParameter("o", "mr")
                    else -> addQueryParameter("o", params.sort)
                }
                if (params.sort in PERIOD_SORTS && params.period.isNotEmpty()) addQueryParameter("t", params.period)
                val categoryId = params.category.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
                    ?: if (params.orientation == "transgender") TRANS_CATEGORY_ID else null
                categoryId?.let { addQueryParameter("filter_category", it) }
                applyCommon(params)
            }
            .addPage(page)
            .build()
    }

    private fun pornstarUrl(params: PornhubFilters.Params, page: Int): HttpUrl {
        val builder = "$baseUrl/pornstar/${params.pornstar}/videos".toHttpUrl().newBuilder()
        if (params.sort in PORNSTAR_SORTS) builder.addQueryParameter("o", params.sort)
        return builder.addPage(page).build()
    }

    private fun browseUrl(params: PornhubFilters.Params, page: Int): HttpUrl {
        val category = params.category
        val path = when {
            category.startsWith("/") && params.orientation.isEmpty() -> category
            params.orientation == "gay" -> "/gay/video"
            params.orientation == "transgender" -> "/transgender"
            else -> "/video"
        }
        return "$baseUrl$path".toHttpUrl().newBuilder()
            .apply {
                if (category.isNotEmpty() && category.all(Char::isDigit)) addQueryParameter("c", category)
                when (params.sort) {
                    "" -> {}
                    "new" -> addQueryParameter("o", "cm")
                    else -> addQueryParameter("o", params.sort)
                }
                if (params.sort in PERIOD_SORTS && params.period.isNotEmpty()) addQueryParameter("t", params.period)
                applyCommon(params)
            }
            .addPage(page)
            .build()
    }

    private fun HttpUrl.Builder.applyCommon(params: PornhubFilters.Params) {
        if (params.production.isNotEmpty()) addQueryParameter("p", params.production)
        if (params.hd) addQueryParameter("hd", "1")
        params.duration.split('&').filter { '=' in it }.forEach {
            addQueryParameter(it.substringBefore('='), it.substringAfter('='))
        }
    }

    private fun HttpUrl.Builder.addPage(page: Int): HttpUrl.Builder = apply {
        if (page > 1) addQueryParameter("page", page.toString())
    }

    /** A pasted Pornhub link (or `viewkey`) becomes either a single video or an uploader listing. */
    private fun linkTarget(query: String): String? {
        if (VIEWKEY_ONLY_REGEX.matches(query) && query.any(Char::isDigit) && query.any(Char::isLetter)) {
            return "/view_video.php?viewkey=$query"
        }
        val url = query.toHttpUrlOrNull()
            ?: query.takeIf { it.startsWith("/") }?.let { "$baseUrl$it".toHttpUrlOrNull() }
            ?: return null
        if (!url.host.endsWith("pornhub.com") && !url.host.endsWith("pornhub.org")) return null
        url.queryParameter("viewkey")?.let { return "/view_video.php?viewkey=$it" }
        val segments = url.pathSegments.filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        if (segments.first() == "embed" && segments.size > 1) return "/view_video.php?viewkey=${segments[1]}"
        return if (segments.first() in UPLOADER_SEGMENTS && segments.size >= 2) {
            "/${segments[0]}/${segments[1]}/videos"
        } else {
            url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        }
    }

    private suspend fun openLink(path: String, page: Int): AnimesPage {
        if (path.startsWith("/view_video.php")) {
            val anime = SAnime.create().apply { url = path }
            return AnimesPage(listOf(getAnimeDetails(anime).apply { url = path }), false)
        }
        val url = "$baseUrl$path".toHttpUrl().newBuilder().addPage(page).build()
        return videoListing(url)
    }

    // ============================== Listings ==============================

    private suspend fun videoListing(url: HttpUrl): AnimesPage {
        val doc = client.get(url, pageHeaders).useAsJsoup()
        val animes = doc.select(VIDEO_ITEM_SELECTOR)
            .filterNot { item -> item.isSidebarItem() }
            .mapNotNull { it.toSAnime() }
            .distinctBy { it.url }
        val hasNext = doc.selectFirst("li.page_next:not(.disabled) a[href]") != null ||
            (doc.selectFirst("#moreDataBtn, .js-loadMoreData") != null && animes.isNotEmpty())
        return AnimesPage(animes, hasNext)
    }

    private fun Element.isSidebarItem(): Boolean {
        val parent = parent() ?: return false
        return parent.id() in SIDEBAR_LISTS || parent.hasClass("display-grid") || hasClass("premiumVideoBlock")
    }

    private fun Element.toSAnime(): SAnime? {
        val vkey = attr("data-video-vkey").ifBlank {
            selectFirst("a[href*=viewkey]")?.attr("href")?.substringAfter("viewkey=")?.substringBefore('&')
        }?.ifBlank { null } ?: return null
        val link = selectFirst("span.title a, .title a, a.linkVideoThumb")
        val img = selectFirst("img")
        return SAnime.create().apply {
            url = "/view_video.php?viewkey=$vkey"
            title = (link?.attr("title")?.ifBlank { null } ?: link?.text() ?: img?.attr("alt")).orEmpty().trim()
            thumbnail_url = img?.let { image ->
                listOf("data-mediumthumb", "data-image", "data-thumb_url", "data-src", "src")
                    .firstNotNullOfOrNull { attribute -> image.absUrl(attribute).takeIf { it.startsWith("http") } }
            }
            author = selectFirst(".usernameWrap a, .videoUploaderBlock a")?.text()?.ifBlank { null }
            update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
            status = SAnime.COMPLETED
        }
    }

    // =========================== Video page cache ===========================

    private class VideoPage(
        val doc: Document,
        val flashVars: FlashVarsDto?,
        val uploadDate: String?,
        val fetchedAt: Long,
    )

    private val pageCache = ConcurrentHashMap<String, VideoPage>()

    /**
     * Details, episode list and hosters all come from the same watch page. One fetch serves all three
     * (stream links stay valid for ~2 hours, the cache keeps pages for a few minutes).
     */
    private suspend fun videoPage(path: String, fresh: Boolean = false): VideoPage {
        val key = path.substringAfter("viewkey=").substringBefore('&')
        val now = System.currentTimeMillis()
        if (!fresh) {
            pageCache[key]?.takeIf { now - it.fetchedAt < PAGE_CACHE_MS }?.let { return it }
        }
        val pageUrl = "$baseUrl/view_video.php?viewkey=$key"
        val html = client.get(pageUrl, pageHeaders, CacheControl.FORCE_NETWORK).use { it.body.string() }
        val flashVars = FLASHVARS_REGEX.find(html)?.groupValues?.get(1)?.let { raw ->
            runCatching { raw.parseAs<FlashVarsDto>(lenientJson) }.getOrNull()
        }
        val uploadDate = UPLOAD_DATE_REGEX.find(html)?.groupValues?.get(1)
        val doc = Jsoup.parse(html, pageUrl)
        if (pageCache.size > PAGE_CACHE_SIZE) {
            pageCache.entries.filter { now - it.value.fetchedAt > PAGE_CACHE_MS }.forEach { pageCache.remove(it.key) }
            if (pageCache.size > PAGE_CACHE_SIZE) pageCache.clear()
        }
        return VideoPage(doc, flashVars, uploadDate, now).also { pageCache[key] = it }
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val page = videoPage(anime.url)
        val doc = page.doc
        val categories = doc.select(".categoriesWrapper a.item, .categoriesWrapper a[href^=/video], .categoriesWrapper a[href^=/categories]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }
        val tags = doc.select(".tagsWrapper a.item, .tagsWrapper a[href*=search]").map { it.text().trim() }.filter { it.isNotEmpty() }
        val pornstars = doc.select(".pornstarsWrapper a.pstar-list-btn, .pornstarsWrapper a[href^=/pornstar], .pornstarsWrapper a[href^=/model]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val uploader = doc.selectFirst(".video-detailed-info .usernameWrap a, .userInfo .usernameBadgesWrapper a")?.text()?.trim()
        val views = doc.selectFirst(".views .count, .count")?.text()?.trim()
        val up = doc.selectFirst(".votesUp")?.attr("data-rating")?.ifBlank { null } ?: doc.selectFirst(".votesUp")?.text()?.trim()
        val down = doc.selectFirst(".votesDown")?.attr("data-rating")?.ifBlank { null } ?: doc.selectFirst(".votesDown")?.text()?.trim()
        val percent = doc.selectFirst(".ratingPercent .percent, .percent")?.text()?.trim()
        val uploaded = page.uploadDate
        val duration = page.flashVars?.videoDuration

        return SAnime.create().apply {
            url = anime.url
            title = doc.selectFirst("h1.title .inlineFree, h1.title")?.text()?.trim()?.ifBlank { null }
                ?: page.flashVars?.videoTitle ?: anime.title
            thumbnail_url = page.flashVars?.imageUrl?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                ?: anime.thumbnail_url
            author = uploader
            artist = pornstars.joinToString().ifBlank { null }
            genre = (categories + tags).distinctBy { it.lowercase() }.joinToString().ifBlank { null }
            status = SAnime.COMPLETED
            update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
            description = buildString {
                duration?.let { appendLine("Duration: ${it.formatDuration()}") }
                views?.let { appendLine("Views: $it") }
                if (percent != null || up != null) {
                    append("Rating: ")
                    percent?.let { append(it) }
                    if (up != null) append(" (\uD83D\uDC4D $up / \uD83D\uDC4E ${down ?: "0"})")
                    appendLine()
                }
                uploaded?.let { appendLine("Uploaded: ${it.substringBefore('T')}") }
                uploader?.let { appendLine("Uploader: $it") }
                if (pornstars.isNotEmpty()) appendLine("Pornstars: ${pornstars.joinToString()}")
                if (tags.isNotEmpty()) appendLine("Tags: ${tags.joinToString()}")
            }.trim().ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val page = videoPage(anime.url)
        val uploaded = page.uploadDate
        val best = page.flashVars?.mediaDefinitions?.mapNotNull { it.resolution() }?.maxOrNull()
        return listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Video"
                episode_number = 1F
                date_upload = DATE_FORMAT.tryParse(uploaded)
                scanlator = listOfNotNull(
                    best?.let { "${it}p" },
                    page.flashVars?.videoDuration?.formatDuration(),
                ).joinToString(" \u2022 ").ifBlank { null }
                preview_url = page.flashVars?.imageUrl
            },
        )
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = coroutineScope {
        // Stream links stay valid for ~2 hours, so the page fetched for the details screen is reused.
        val page = videoPage(episode.url)
        val viewkey = episode.url.substringAfter("viewkey=").substringBefore('&')
        val flashVars = page.flashVars ?: throw Exception(unavailableMessage(page.doc))
        if (flashVars.videoUnavailable == true && flashVars.mediaDefinitions.isEmpty()) {
            throw Exception(unavailableMessage(page.doc))
        }

        val hlsVideos = flashVars.mediaDefinitions
            .filter { it.format == "hls" && it.videoUrl.startsWith("http") && it.resolution() != null }
            .distinctBy { it.resolution() }
            .map { def ->
                val res = def.resolution()!!
                Video(
                    videoUrl = if (prefAccelerator) {
                        proxy.registerHls("h${viewkey}_$res", def.videoUrl, videoHeaders) { freshHlsUrl(episode.url, res) }
                    } else {
                        def.videoUrl
                    },
                    videoTitle = "HLS \u2022 ${res}p",
                    resolution = res,
                    headers = videoHeaders,
                    mpvArgs = mpvArgs(),
                    initialized = true,
                )
            }
            .sortVideos()

        val mp4Source = flashVars.mediaDefinitions.firstOrNull { it.format == "mp4" && it.videoUrl.contains("get_media") }

        // No reachability probe any more: the accelerator refreshes refused HLS links on its own.
        val mp4 = mp4Source?.let { runCatching { mp4Videos(it.videoUrl, episode.url, viewkey) }.getOrNull() }.orEmpty()

        // Start downloading the stream the player will most likely open first.
        if (prefAccelerator) {
            val first = if (prefServer == SERVER_MP4) {
                mp4.firstOrNull() ?: hlsVideos.firstOrNull()
            } else {
                hlsVideos.firstOrNull() ?: mp4.firstOrNull()
            }
            first?.videoUrl?.let { url ->
                val key = url.substringAfter("/mp4/").substringAfter("/hls/").substringBefore('/').substringBefore('.')
                proxy.prewarm(key)
            }
        }

        buildList {
            if (hlsVideos.isNotEmpty()) {
                add(Hoster(hosterUrl = getEpisodeUrl(episode), hosterName = SERVER_HLS, videoList = hlsVideos))
            }
            if (mp4.isNotEmpty()) {
                add(Hoster(hosterUrl = getEpisodeUrl(episode), hosterName = SERVER_MP4, videoList = mp4))
            }
        }.ifEmpty { throw Exception(unavailableMessage(page.doc)) }.sortHosters()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    /** `video/get_media` returns direct progressive MP4 files; it needs the cookies set by the watch page. */
    private suspend fun mp4Videos(getMediaUrl: String, episodeUrl: String, viewkey: String): List<Video> {
        val definitions = mp4Definitions(getMediaUrl)
        return definitions
            .filter { it.videoUrl.startsWith("http") && it.resolution() != null }
            .distinctBy { it.resolution() }
            .map { def ->
                val res = def.resolution()!!
                Video(
                    videoUrl = if (prefAccelerator) {
                        proxy.registerMp4("m${viewkey}_$res", def.videoUrl, videoHeaders) { freshMp4Url(episodeUrl, res) }
                    } else {
                        def.videoUrl
                    },
                    videoTitle = "MP4 \u2022 ${res}p",
                    resolution = res,
                    headers = videoHeaders,
                    mpvArgs = mpvArgs(),
                    initialized = true,
                )
            }
            .sortVideos()
    }

    private suspend fun mp4Definitions(getMediaUrl: String): List<MediaDefinitionDto> {
        val url = if (getMediaUrl.startsWith("http")) getMediaUrl else baseUrl + getMediaUrl
        return client.get(url, ajaxHeaders, CacheControl.FORCE_NETWORK).parseAs<List<MediaDefinitionDto>>(lenientJson)
    }

    /** Used by the accelerator when a stream link expired or a CDN node refused it. */
    private suspend fun freshHlsUrl(episodeUrl: String, res: Int): String? = videoPage(episodeUrl, fresh = true).flashVars
        ?.mediaDefinitions
        ?.firstOrNull { it.format == "hls" && it.resolution() == res && it.videoUrl.startsWith("http") }
        ?.videoUrl

    private suspend fun freshMp4Url(episodeUrl: String, res: Int): String? {
        val getMedia = videoPage(episodeUrl, fresh = true).flashVars?.mediaDefinitions
            ?.firstOrNull { it.format == "mp4" && it.videoUrl.contains("get_media") }?.videoUrl ?: return null
        return mp4Definitions(getMedia).firstOrNull { it.resolution() == res && it.videoUrl.startsWith("http") }?.videoUrl
    }

    private fun unavailableMessage(doc: Document): String = doc.selectFirst(".geoBlocked, .removed, .video-wrapper .notice, .userMessageSection")
        ?.text()?.trim()?.ifBlank { null }?.take(200)
        ?: "This video is unavailable (removed, private or premium only)"

    /** Bigger demuxer cache and FFmpeg auto-reconnect so a dropped connection resumes instead of failing. */
    private fun mpvArgs(): List<Pair<String, String>> = listOf(
        "cache" to "yes",
        "demuxer-max-bytes" to "${prefBuffer}MiB",
        "demuxer-max-back-bytes" to "${(prefBuffer.toIntOrNull() ?: 96) / 2}MiB",
        "network-timeout" to "30",
        "stream-lavf-o" to "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5",
    )

    override fun List<Hoster>.sortHosters(): List<Hoster> = sortedByDescending { it.hosterName == prefServer }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferred = prefQuality.toIntOrNull() ?: 1080
        return sortedWith(
            compareByDescending<Video> { it.resolution == preferred }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================= Utilities ==============================

    private fun MediaDefinitionDto.resolution(): Int? = ((quality as? JsonPrimitive)?.contentOrNull?.toIntOrNull()) ?: height

    private fun Long.formatDuration(): String {
        val h = this / 3600
        val m = (this % 3600) / 60
        val s = this % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s\nHLS starts fastest and seeks instantly; MP4 is a direct file that works with downloads.",
            entries = listOf(SERVER_HLS, SERVER_MP4),
            entryValues = listOf(SERVER_HLS, SERVER_MP4),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "240p"),
            entryValues = listOf("1080", "720", "480", "240"),
        )
        screen.addListPreference(
            key = PREF_POPULAR_KEY,
            default = PREF_POPULAR_DEFAULT,
            title = "Popular tab shows",
            summary = "%s",
            entries = listOf("Most Viewed", "Hottest", "Top Rated", "Featured"),
            entryValues = listOf("mv", "ht", "tr", "fr"),
        )
        screen.addListPreference(
            key = PREF_PERIOD_KEY,
            default = PREF_PERIOD_DEFAULT,
            title = "Popular period",
            summary = "%s\nUsed with Most Viewed and Top Rated.",
            entries = listOf("Today", "This Week", "This Month", "This Year", "All Time"),
            entryValues = listOf("t", "w", "m", "y", "a"),
        )
        screen.addListPreference(
            key = PREF_LATEST_MIN_KEY,
            default = PREF_LATEST_MIN_DEFAULT,
            title = "Latest tab minimum length",
            summary = "%s\nShort videos are still available with the Duration filter.",
            entries = listOf("10+ minutes", "20+ minutes", "30+ minutes", "Any length (includes short videos)"),
            entryValues = listOf("10", "20", "30", ""),
        )
        screen.addSwitchPreference(
            key = PREF_ACCEL_KEY,
            default = PREF_ACCEL_DEFAULT,
            title = "Fast playback (stream accelerator)",
            summary = "Downloads MP4 in parallel pieces and prefetches HLS segments, refreshing expired links. " +
                "Turn off only if playback fails.",
        )
        screen.addSwitchPreference(
            key = PREF_DOH_KEY,
            default = PREF_DOH_DEFAULT,
            title = "Bypass DNS blocking (DNS over HTTPS)",
            summary = "Resolves the site through Cloudflare/Google DNS over HTTPS, " +
                "so it loads without a VPN when your ISP blocks it by DNS.",
        )
        screen.addListPreference(
            key = PREF_BUFFER_KEY,
            default = PREF_BUFFER_DEFAULT,
            title = "Buffer size",
            summary = "%s\nHow much video the player downloads ahead.",
            entries = listOf("Normal (64 MiB)", "Bigger (96 MiB)", "Large (128 MiB)", "Extra large (192 MiB)"),
            entryValues = listOf("64", "96", "128", "192"),
        )
    }

    companion object {
        private const val SERVER_HLS = "HLS"
        private const val SERVER_MP4 = "MP4"

        private const val TRANS_CATEGORY_ID = "83"

        private val PERIOD_SORTS = setOf("mv", "tr")
        private val PORNSTAR_SORTS = setOf("mv", "tr", "lg", "ht")
        private const val PORNSTAR_PAGES = 3
        private const val PORNSTAR_TTL_MS = 3 * 24 * 60 * 60_000L
        private val UPLOADER_SEGMENTS = setOf("model", "pornstar", "channels", "users")
        private val SIDEBAR_LISTS = setOf("hottestMenuSection", "recommMenuSection", "bottomVideos")

        private const val VIDEO_ITEM_SELECTOR = "li.pcVideoListItem, li.videoblock"

        private val FLASHVARS_REGEX = Regex("""var\s+flashvars_\d+\s*=\s*(\{.*?\});\s*\n""", RegexOption.DOT_MATCHES_ALL)
        private val UPLOAD_DATE_REGEX = Regex(""""uploadDate"\s*:\s*"([^"]+)"""")
        private val VIEWKEY_ONLY_REGEX = Regex("""^(ph)?[0-9a-f]{10,16}$""")

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

        private const val PAGE_CACHE_MS = 5 * 60_000L
        private const val PAGE_CACHE_SIZE = 40

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = SERVER_HLS
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_POPULAR_KEY = "popular_sort"
        private const val PREF_POPULAR_DEFAULT = "mv"
        private const val PREF_PERIOD_KEY = "popular_period"
        private const val PREF_PERIOD_DEFAULT = "w"
        private const val PREF_BUFFER_KEY = "buffer_size_mib"
        private const val PREF_BUFFER_DEFAULT = "96"
        private const val PREF_ACCEL_KEY = "stream_accelerator"
        private const val PREF_ACCEL_DEFAULT = true
        private const val PREF_DOH_KEY = "dns_over_https"
        private const val PREF_DOH_DEFAULT = true
        private const val PREF_LATEST_MIN_KEY = "latest_min_duration"
        private const val PREF_LATEST_MIN_DEFAULT = "10"
        private const val PREF_PORNSTARS_KEY = "pornstar_cache"
        private const val PREF_PORNSTARS_TIME_KEY = "pornstar_cache_time"
    }
}
