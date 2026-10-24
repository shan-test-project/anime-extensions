package eu.kanade.tachiyomi.animeextension.en.av1encodes

import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.Source
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.formatBytes
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class AV1Encodes : Source() {
    override val name = "AV1Encodes"

    override val lang = "en"

    override val supportsLatest = true

    override val baseUrl: String
        get() {
            val mirror = preferences.getString(PREF_MIRROR_KEY, PREF_MIRROR_DEFAULT) ?: PREF_MIRROR_DEFAULT
            return if (mirror == MIRROR_CUSTOM) customDomain() ?: PREF_MIRROR_DEFAULT else mirror
        }

    private fun customDomain(): String? = preferences
        .getString(PREF_CUSTOM_KEY, "")
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.toHttpUrlOrNull() != null }

    /** Every host that serves the site: the built-in mirrors plus an optional custom domain. */
    private fun mirrorHosts(): List<String> {
        val selected = baseUrl.toHttpUrlOrNull()?.host
        val custom = customDomain()?.toHttpUrlOrNull()?.host
        return listOfNotNull(selected, custom).plus(MIRRORS.mapNotNull { it.toHttpUrlOrNull()?.host }).distinct()
    }

    private val prefQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val prefServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    private val showTorrent by preferences.delegate(PREF_TORRENT_KEY, PREF_TORRENT_DEFAULT)

    private val loadCovers by preferences.delegate(PREF_COVERS_KEY, PREF_COVERS_DEFAULT)

    override val client: OkHttpClient =
        network.client
            .newBuilder()
            .addInterceptor(SiteInterceptor(::mirrorHosts))
            .addInterceptor(ChallengeInterceptor(CloudflareInterceptor(network.client)))
            .rateLimit(RATE_LIMIT_PERMITS, 1.seconds) { it.host in mirrorHosts() }
            .build()

    override fun headersBuilder(): Headers.Builder = super
        .headersBuilder()
        .set("Accept-Language", "en-US,en;q=0.9")

    private fun pageHeaders(referer: String? = null): Headers = headersBuilder()
        .set("Accept", ACCEPT_HTML)
        .set("Referer", referer ?: "$baseUrl/")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "same-origin")
        .set("Upgrade-Insecure-Requests", "1")
        .build()

    private fun apiHeaders(
        referer: String,
        token: String?,
    ): Headers = headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", referer)
        .set("Origin", baseUrl)
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .apply { if (!token.isNullOrBlank()) set("X-DDL-Token", token) }
        .build()

    /** Headers handed to the player; site cookies (av1_client_id, cf_clearance) go along for site-hosted links. */
    private fun videoHeaders(url: String): Headers {
        val builder =
            headersBuilder()
                .set("Accept", "*/*")
                .set("Referer", "$baseUrl/")
                .set("Origin", baseUrl)
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null && httpUrl.host in mirrorHosts()) {
            val cookies = client.cookieJar.loadForRequest(httpUrl)
            if (cookies.isNotEmpty()) builder.set("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
        }
        return builder.build()
    }

    private suspend fun fetchDocument(
        url: String,
        referer: String? = null,
    ): Document = client.get(url, pageHeaders(referer)).useAsJsoup()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return fetchCatalog(AV1EncodesFilters.Params(sort = "a-z"), page - 1)

        val home = fetchDocument("$baseUrl/")
        val trending = home.select(".spotlight-slide").flatMap(::parseCards)
        val topDownloads =
            runCatching { parseCards(fetchDocument("$baseUrl/stats").selectFirst("main") ?: Document("")) }
                .getOrDefault(emptyList())

        val animes =
            (trending + topDownloads)
                .distinctBy { it.url }
                .ifEmpty { parseCards(home.selectFirst("main") ?: home) }
        return AnimesPage(animes.fillCovers(), hasNextPage = true)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page > 1) return fetchCatalog(AV1EncodesFilters.Params(sort = "latest"), page - 1)

        val home = fetchDocument("$baseUrl/")
        val sections = home.select("#episodeGrid, .episode-grid, #latestCompletedList, .mini-list")
        val animes =
            (if (sections.isEmpty()) parseCards(home.selectFirst("main") ?: home) else sections.flatMap(::parseCards))
                .distinctBy { it.url }
        return AnimesPage(animes.fillCovers(), hasNextPage = true)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val trimmed = query.trim()

        // A pasted site link opens that title directly.
        animePath(trimmed)?.takeIf { trimmed.startsWith("http") }?.let { path ->
            val anime = getAnimeDetails(SAnime.create().apply { url = path })
            anime.url = path
            return AnimesPage(listOf(anime), false)
        }

        if (trimmed.isNotEmpty()) {
            val url =
                "$baseUrl/search"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", trimmed)
                    .apply { if (page > 1) addQueryParameter("page", page.toString()) }
                    .build()
            val doc = fetchDocument(url.toString())
            val root = doc.selectFirst(".search-results-grid, .anime-list-items, .search-results-container, main") ?: doc
            return AnimesPage(parseCards(root).fillCovers(), doc.hasNextPage())
        }

        val params = AV1EncodesFilters.parse(filters)
        if (params.list.isNotEmpty()) {
            val url =
                "$baseUrl/${params.list}"
                    .toHttpUrl()
                    .newBuilder()
                    .apply { if (page > 1) addQueryParameter("page", page.toString()) }
                    .build()
            val doc = fetchDocument(url.toString())
            return AnimesPage(parseCards(doc.selectFirst("main") ?: doc).fillCovers(), doc.hasNextPage())
        }
        return fetchCatalog(params, page)
    }

    private suspend fun fetchCatalog(
        params: AV1EncodesFilters.Params,
        page: Int,
    ): AnimesPage {
        val url =
            "$baseUrl/anime"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    addQueryParameter("sort", params.sort.ifEmpty { "latest" })
                    if (params.genres.isNotEmpty()) addQueryParameter("genres", params.genres.joinToString(","))
                    if (params.yearFrom.isNotEmpty()) addQueryParameter("year_from", params.yearFrom)
                    if (params.yearTo.isNotEmpty()) addQueryParameter("year_to", params.yearTo)
                    if (params.letter.isNotEmpty()) addQueryParameter("letter", params.letter)
                    addQueryParameter("page", page.toString())
                }.build()
        val doc = fetchDocument(url.toString())
        val root = doc.selectFirst("#anime-list, .anime-index-list, .anime-list, main") ?: doc
        return AnimesPage(parseCards(root).fillCovers(), doc.hasNextPage())
    }

    override fun getFilterList(): AnimeFilterList = AV1EncodesFilters.getFilterList()

    // ============================ List parsing ============================

    private val coverCache = ConcurrentHashMap<String, String>()

    /**
     * Collects every /anime/<slug> link inside [root] as one entry, reading the
     * title and poster from the surrounding card. Works for the spotlight, the
     * episode grid, the sidebar list, search results, the A–Z index and the
     * "more like this" grid alike.
     */
    private fun parseCards(root: Element): List<SAnime> {
        val result = LinkedHashMap<String, SAnime>()
        root.select("a[href]").forEach { link ->
            val path = animePath(link.absUrl("href").ifEmpty { link.attr("href") }) ?: return@forEach
            val card = link.closest(CARD_SELECTOR) ?: link
            val scope = if (card !== link && card.linkedAnimeCount() > 1) link else card
            val img = scope.selectFirst("img")
            val title =
                scope
                    .selectFirst(TITLE_SELECTOR)
                    ?.text()
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { img?.attr("alt")?.trim().orEmpty() }
                    .ifEmpty { link.attr("title").trim() }
                    .ifEmpty { link.text().trim() }
            if (title.isEmpty() || title.lowercase() in IGNORED_TITLES) return@forEach

            val thumbnail = img?.imageUrl()
            val existing = result[path]
            if (existing == null) {
                result[path] =
                    SAnime.create().apply {
                        url = path
                        this.title = Parser.unescapeEntities(title, false)
                        thumbnail_url = thumbnail ?: coverCache[path]
                    }
            } else if (existing.thumbnail_url == null && thumbnail != null) {
                existing.thumbnail_url = thumbnail
            }
        }
        result.values.forEach { anime -> anime.thumbnail_url?.let { coverCache[anime.url] = it } }
        return result.values.toList()
    }

    private fun Element.linkedAnimeCount(): Int = select("a[href*='/anime/']")
        .mapNotNull { animePath(it.absUrl("href").ifEmpty { it.attr("href") }) }
        .distinct()
        .size

    /** Returns "/anime/<slug>" for links to an anime page on any mirror, otherwise null. */
    private fun animePath(href: String): String? {
        if (href.isBlank()) return null
        val url = if (href.startsWith("http")) href.toHttpUrlOrNull() else "$baseUrl/${href.trimStart('/')}".toHttpUrlOrNull()
        url ?: return null
        if (url.host !in mirrorHosts()) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size != 2 || segments[0] != "anime") return null
        return "/anime/${segments[1]}"
    }

    private fun Element.imageUrl(): String? = listOf("data-src", "data-lazy-src", "data-original", "src")
        .firstNotNullOfOrNull { attr -> absUrl(attr).takeIf { it.isNotBlank() && !it.startsWith("data:") } }

    private fun Document.hasNextPage(): Boolean {
        if (selectFirst(".pagination a.next-page, .pagination a[rel=next], .pagination .next a, a.page-link.next-page") != null) return true
        val active = selectFirst(".pagination .active")?.text()?.trim()?.toIntOrNull() ?: return false
        return select(".pagination a").any { (it.text().trim().toIntOrNull() ?: 0) > active }
    }

    /** The A–Z index has no posters; fill them from the (cached) detail pages. */
    private suspend fun List<SAnime>.fillCovers(): List<SAnime> {
        if (!loadCovers) return this
        val missing = filter { it.thumbnail_url.isNullOrBlank() }
        if (missing.isEmpty()) return this
        missing.parallelMap { anime ->
            val cover = coverCache[anime.url] ?: runCatching { detailDocument(anime.url).detailCover() }.getOrNull()
            if (cover != null) {
                coverCache[anime.url] = cover
                anime.thumbnail_url = cover
            }
        }
        return this
    }

    // ============================== Details ===============================

    private val detailCache = ConcurrentHashMap<String, Pair<Long, Document>>()

    private suspend fun detailDocument(path: String): Document {
        val url = baseUrl + path
        val now = System.currentTimeMillis()
        detailCache[url]?.let { (time, doc) -> if (now - time < DETAIL_TTL_MS) return doc }
        val doc = fetchDocument(url)
        if (detailCache.size > DETAIL_CACHE_SIZE) detailCache.clear()
        detailCache[url] = now to doc
        return doc
    }

    private fun Document.detailCover(): String? =
        selectFirst(".anime-image img, .anime-poster img, img.anime-poster, .anime-card img")?.imageUrl()
            ?: selectFirst("meta[property=og:image]")?.absUrl("content")?.ifEmpty { null }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = detailDocument(anime.url)
        val meta = doc.metaFields()

        fun field(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
            meta.entries
                .firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() && it != "N/A" }
        }

        return SAnime.create().apply {
            url = anime.url
            title = doc
                .selectFirst("h1.anime-title, .anime-details h1, main h1")
                ?.text()
                ?.trim()
                ?.ifEmpty { null } ?: anime.title
            thumbnail_url = doc.detailCover() ?: anime.thumbnail_url
            genre = field("Genre", "Genres")
                ?: doc.select(".anime-tags .tag, .genre-tag").joinToString { it.text().trim() }.ifEmpty { null }
            author = field("Studio", "Studios")
            status =
                when {
                    field(
                        "Status",
                    )?.contains("airing", true) == true ||
                        field("Status")?.contains("ongoing", true) == true -> SAnime.ONGOING
                    field(
                        "Status",
                    )?.contains("complete", true) == true ||
                        field("Status")?.contains("finished", true) == true -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            description =
                buildString {
                    doc
                        .selectFirst("p.anime-synopsis, .anime-synopsis, .synopsis")
                        ?.text()
                        ?.trim()
                        ?.let(::append)
                    val extra =
                        meta
                            .filterKeys { key -> DETAIL_SKIP_KEYS.none { it.equals(key, ignoreCase = true) } }
                            .filterValues { it.isNotBlank() && it != "N/A" }
                    if (extra.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        extra.forEach { (key, value) -> append(key).append(": ").append(value).append('\n') }
                    }
                    val seasons = doc.seasonQualities(slugOf(anime.url)).keys
                    if (seasons.size > 1) {
                        if (isNotEmpty()) append(if (endsWith('\n')) "" else "\n")
                        append("Seasons: ").append(seasons.joinToString { seasonLabel(it) })
                    }
                }.trim().ifEmpty { null }
            initialized = true
        }
    }

    /** "Episodes: 12 | Genre: Drama, Mystery" → {Episodes=12, Genre=Drama, Mystery}. */
    private fun Document.metaFields(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        select(".anime-meta, .anime-details .meta, .anime-details p:not(.anime-synopsis)").forEach { element ->
            element.text().split('|').forEach { part ->
                val key = part.substringBefore(':', "").trim()
                val value = part.substringAfter(':', "").trim()
                if (key.isNotEmpty() && key.length <= 24 && value.isNotEmpty()) result.putIfAbsent(key, value)
            }
        }
        return result
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val doc = detailDocument(anime.url)
        val root = doc.selectFirst(".more-like-this, .similar-anime-grid, .similar-anime") ?: return emptyList()
        return parseCards(root).filter { it.url != anime.url }
    }

    // ============================== Episodes ==============================

    /**
     * Season → (height → resolution label) as linked from the detail page's
     * quality grid, e.g. "1" → {1080="1920 x 1080", 720="1280 x 720"}.
     */
    private fun Document.seasonQualities(slug: String): Map<String, Map<Int, String>> {
        val result = LinkedHashMap<String, LinkedHashMap<Int, String>>()
        select("a[href*='/episodes/']").forEach { link ->
            val segments = link.absUrl("href").toHttpUrlOrNull()?.pathSegments ?: return@forEach
            if (segments.size < 4 || segments[0] != "episodes" || segments[1] != slug) return@forEach
            val height = heightOf(segments[3]) ?: return@forEach
            result.getOrPut(segments[2]) { LinkedHashMap() }.putIfAbsent(height, segments[3])
        }
        if (result.isEmpty()) {
            select(".season-option[data-season], [data-season]")
                .map { it.attr("data-season").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { result[it] = LinkedHashMap(STANDARD_RESOLUTIONS) }
        }
        return result.entries
            .sortedWith(compareBy<Map.Entry<String, *>> { it.key.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.key })
            .associate { it.key to it.value }
    }

    private suspend fun seasonResolutions(
        slug: String,
        season: String,
    ): Map<Int, String> {
        val fromPage = runCatching { detailDocument("/anime/$slug").seasonQualities(slug)[season] }.getOrNull()
        return fromPage?.takeIf { it.isNotEmpty() } ?: STANDARD_RESOLUTIONS
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val ref = EpisodeRef.parse(episode.url) ?: return baseUrl + episode.url
        return listingUrl(ref.slug, ref.season, ref.resolution).toString()
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = slugOf(anime.url)
        val seasons =
            detailDocument(anime.url)
                .seasonQualities(slug)
                .ifEmpty { mapOf("1" to STANDARD_RESOLUTIONS) }
        val multiSeason = seasons.size > 1
        val seasonOrder = seasons.keys.withIndex().associate { (index, season) -> season to index }

        val entries =
            seasons.entries.toList().parallelCatchingFlatMap { (season, qualities) ->
                for (resolution in listingOrder(qualities)) {
                    val list = fetchListing(slug, season, resolution)
                    if (list.isNotEmpty()) return@parallelCatchingFlatMap list
                }
                emptyList()
            }
        if (entries.isEmpty()) throw Exception("No episodes found. If the site works in WebView, try again in a moment.")

        val single = entries.size == 1
        return entries
            .distinctBy { it.href.substringBefore('?') }
            .sortedWith(
                compareByDescending<EpisodeEntry> { seasonOrder[it.season] ?: 0 }
                    .thenByDescending { it.number }
                    .thenBy { it.audio.orEmpty() },
            ).map { entry ->
                SEpisode.create().apply {
                    url = entry.href.substringBefore('?')
                    name = episodeName(entry, multiSeason, single)
                    episode_number = entry.number
                    scanlator = entry.audio
                }
            }
    }

    private fun listingOrder(qualities: Map<Int, String>): List<String> {
        val preferred = prefQuality.toIntOrNull()
        return qualities.entries
            .sortedWith(compareByDescending<Map.Entry<Int, String>> { it.key == preferred }.thenByDescending { it.key })
            .map { it.value }
    }

    private fun episodeName(
        entry: EpisodeEntry,
        multiSeason: Boolean,
        single: Boolean,
    ): String {
        val isMovie = entry.season.equals("movie", ignoreCase = true)
        if (isMovie && single) return "Movie"
        val episode = "Episode ${formatNumber(entry.number)}"
        val label = entry.label.trim()
        val extra =
            label.takeIf {
                it.isNotEmpty() && !EPISODE_ONLY_LABEL.matches(it) && !it.contains(entry.fileName)
            }
        return buildString {
            if (multiSeason || isMovie) append(seasonLabel(entry.season)).append(" · ")
            append(episode)
            if (extra != null) append(": ").append(extra)
        }
    }

    private val listingCache = ConcurrentHashMap<String, Pair<Long, List<EpisodeEntry>>>()

    private fun listingUrl(
        slug: String,
        season: String,
        resolution: String,
    ): HttpUrl = baseUrl
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("episodes")
        .addPathSegment(slug)
        .addPathSegment(season)
        .addPathSegment(resolution)
        .build()

    /** Episode files of one season at one resolution; empty when that combination does not exist. */
    private suspend fun fetchListing(
        slug: String,
        season: String,
        resolution: String,
        fresh: Boolean = false,
    ): List<EpisodeEntry> {
        val url = listingUrl(slug, season, resolution).toString()
        val now = System.currentTimeMillis()
        if (!fresh) listingCache[url]?.let { (time, list) -> if (now - time < LISTING_TTL_MS) return list }

        val response = client.get(url, pageHeaders("$baseUrl/anime/$slug"), ensureSuccess = false)
        if (response.code == 404) {
            response.close()
            return emptyList()
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code} while loading the episode list")
        }
        val entries = parseListing(response.bodyString(), season, url)
        if (listingCache.size > LISTING_CACHE_SIZE) listingCache.clear()
        listingCache[url] = now to entries
        return entries
    }

    /** The /episodes/ endpoint answers either an HTML page or a JSON array of {num, label, audio, href, ddl}. */
    private fun parseListing(
        body: String,
        season: String,
        pageUrl: String,
    ): List<EpisodeEntry> {
        val text = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (text.startsWith("[")) {
            val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            if (array != null) {
                return array.mapIndexedNotNull { index, element ->
                    val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                    buildEntry(
                        season = season,
                        href = obj.string("href") ?: return@mapIndexedNotNull null,
                        label = obj.string("label").orEmpty(),
                        number = obj.string("num")?.toFloatOrNull(),
                        audio = obj.string("audio"),
                        ddl = (obj["ddl"] as? JsonObject)?.toString(),
                        index = index,
                    )
                }
            }
        }

        val doc = Jsoup.parse(body, pageUrl)
        val links = doc.select(".episode-item a[href*='/download/']").ifEmpty { doc.select("a[href*='/download/']") }
        return links.mapIndexedNotNull { index, link ->
            buildEntry(
                season = season,
                href = link.attr("href"),
                label = link.selectFirst(".episode-label")?.text() ?: link.ownText(),
                number = null,
                audio = null,
                ddl = null,
                index = index,
            )
        }
    }

    private fun buildEntry(
        season: String,
        href: String,
        label: String,
        number: Float?,
        audio: String?,
        ddl: String?,
        index: Int,
    ): EpisodeEntry? {
        val path = sitePath(href.replace("&amp;", "&")) ?: return null
        if (!path.startsWith("/download/")) return null
        val fileName = decode(path.substringBefore('?').substringAfterLast('/'))
        val episodeNumber =
            number?.takeIf { it > 0 }
                ?: EPISODE_REGEX
                    .find(fileName)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull()
                ?: LABEL_NUMBER_REGEX.find(label)?.value?.toFloatOrNull()
                ?: (index + 1).toFloat()
        val audioTag =
            audio?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
                ?: AUDIO_REGEX
                    .find(fileName)
                    ?.groupValues
                    ?.get(1)
                    ?.replaceFirstChar { it.uppercase() }
        return EpisodeEntry(season, episodeNumber, label.trim(), audioTag, path, fileName, ddl)
    }

    // =============================== Hosters ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val ref =
            EpisodeRef.parse(episode.url)
                ?: throw Exception("Old episode link — refresh the episode list (pull down on the anime page)")
        val resolutions = seasonResolutions(ref.slug, ref.season)

        val hosters =
            resolutions.entries.toList().parallelCatchingMapNotNull { (height, resolution) ->
                val entries = fetchListing(ref.slug, ref.season, resolution)
                val match = entries.findMatch(ref) ?: return@parallelCatchingMapNotNull null
                val data =
                    HosterData(
                        href = match.href,
                        referer = listingUrl(ref.slug, ref.season, resolution).toString(),
                        resolution = height,
                        audio = match.audio,
                        ddl = match.ddl,
                    )
                Hoster(
                    hosterUrl = baseUrl + match.href,
                    hosterName = "${height}p" + (match.audio?.let { " · $it" } ?: ""),
                    internalData = data.toJsonString(),
                    lazy = true,
                )
            }
        if (hosters.isEmpty()) throw Exception("No servers found for this episode")
        return hosters.sortHosters()
    }

    private fun List<EpisodeEntry>.findMatch(ref: EpisodeRef): EpisodeEntry? {
        val key = ref.normalizedName
        firstOrNull { normalizeFileName(it.fileName) == key }?.let { return it }
        val number = ref.number
        if (number != null) {
            val sameNumber = filter { it.number == number }
            return sameNumber.firstOrNull { it.audio.equals(ref.audio, ignoreCase = true) } ?: sameNumber.firstOrNull()
        }
        return singleOrNull()
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val preferred = prefQuality.toIntOrNull()
        return sortedWith(
            compareByDescending<Hoster> { heightOf(it.hosterName) == preferred }
                .thenByDescending { heightOf(it.hosterName) ?: 0 },
        )
    }

    // =============================== Videos ===============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        hoster.videoList?.takeIf { it.isNotEmpty() }?.let { return it }
        val data = hoster.internalData.parseAs<HosterData>()

        val embedded = data.ddl?.let { runCatching { parseDdl(json.parseToJsonElement(it)) }.getOrNull() }
        val links =
            embedded?.takeIf { it.hasPlayable() } ?: try {
                resolveDownloadPage(data)
            } catch (e: Exception) {
                // The ?token= on download links is time-limited: reload the listing once for a fresh one.
                val refreshed = refreshHref(data) ?: throw e
                resolveDownloadPage(data.copy(href = refreshed))
            }

        val videos = buildVideos(links, data)
        if (videos.isEmpty()) throw Exception("The site returned no playable link for ${hoster.hosterName}")
        return videos.sortVideos()
    }

    private suspend fun refreshHref(data: HosterData): String? {
        val ref = EpisodeRef.parse(data.href) ?: return null
        val entries = fetchListing(ref.slug, ref.season, ref.resolution, fresh = true)
        return entries.findMatch(ref)?.href?.takeIf { it != data.href }
    }

    /**
     * The download page sits behind a human-verification widget, but its markup
     * already carries the X-DDL-Token that authorises the site's own /get_ddl/
     * API, plus the /watch/ player link. Reading those directly skips the
     * captcha entirely.
     */
    private suspend fun resolveDownloadPage(data: HosterData): DdlLinks {
        val pageUrl = baseUrl + data.href
        val html = client.get(pageUrl, pageHeaders(data.referer.toSiteUrl())).bodyString()
        val token = extractDdlToken(html)
        val pageWatch = extractWatchLink(html)
        val fileName =
            PAGE_FILENAME_REGEX
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.let { Parser.unescapeEntities(it, false) }
                ?.takeIf { it.isNotBlank() }
                ?: decode(data.href.substringBefore('?').substringAfterLast('/'))

        val ddl = fetchDdl(fileName, data.href, token, pageUrl)
        return when {
            ddl != null -> if (ddl.watch == null && pageWatch != null) ddl.copy(watch = pageWatch) else ddl
            pageWatch != null -> DdlLinks(null, null, pageWatch, null, null, fileName, null, emptyList(), emptyList())
            else -> throw Exception("Could not read the download token. Open the episode in WebView once, then retry.")
        }
    }

    private suspend fun fetchDdl(
        fileName: String,
        href: String,
        token: String?,
        referer: String,
    ): DdlLinks? {
        val downloadPath = decode(href.substringBefore('?')).removePrefix("/download/")
        val candidates = listOf(fileName, downloadPath).distinct()
        for (candidate in candidates) {
            val url =
                baseUrl
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegment("get_ddl")
                    .apply { candidate.split('/').forEach { addEncodedPathSegment(encodeComponent(it)) } }
                    .build()
            val body =
                runCatching {
                    client.get(url, apiHeaders(referer, token), ensureSuccess = false).bodyString()
                }.getOrNull() ?: continue
            val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: continue
            val links = parseDdl(element)
            if (links != null && links.hasPlayable()) return links
        }
        return null
    }

    private fun parseDdl(element: JsonElement): DdlLinks? {
        val root = element as? JsonObject ?: return null
        val obj = root["ddl"] as? JsonObject ?: root
        if (obj.string("success") == "false" && obj.string("download_link") == null) return null

        fun link(vararg keys: String) = keys
            .firstNotNullOfOrNull { key -> obj.string(key)?.takeIf { it.isNotBlank() } }
            ?.let(::absolute)

        val audio =
            ((obj["audio_details"] as? JsonObject)?.get("audio") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.string("language")?.takeIf(String::isNotBlank) }
                ?.distinct()
                .orEmpty()

        val subtitles =
            (obj["subtitles"] as? JsonArray)
                ?.mapNotNull { item ->
                    val sub = item as? JsonObject ?: return@mapNotNull null
                    val url = listOf("url", "file", "src", "link").firstNotNullOfOrNull { sub.string(it) } ?: return@mapNotNull null
                    val label =
                        listOfNotNull(sub.string("language") ?: sub.string("label"), sub.string("format"))
                            .joinToString(" ")
                            .ifEmpty { "Subtitle" }
                    absolute(url) to label
                }.orEmpty()

        val size = obj.string("file_size")?.let { raw -> raw.toLongOrNull()?.formatBytes() ?: raw }

        return DdlLinks(
            stream = link("stream_link", "streamLink", "stream_url"),
            download = link("download_link", "downloadLink", "ddl_link", "direct_link"),
            watch = link("watch_link", "watchLink", "player_link"),
            dash = link("dash_link", "dashLink", "manifest_link", "mpd_link", "dash_url"),
            torrent = link("torrent_link", "torrentLink", "magnet_link", "magnet"),
            fileName = obj.string("file_name"),
            fileSize = size,
            audioLanguages = audio,
            subtitles = subtitles,
        )
    }

    private fun DdlLinks.hasPlayable() = stream != null || download != null || watch != null || dash != null || torrent != null

    private fun DdlLinks.dashUrl(): String? {
        dash?.let { return it }
        val watchUrl = watch ?: return null
        if (watchUrl.contains(".mpd")) return watchUrl
        if (!watchUrl.contains("/watch/")) return null
        return watchUrl
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .replace("/watch/", "/dash/") + "/manifest.mpd"
    }

    private suspend fun buildVideos(
        links: DdlLinks,
        data: HosterData,
    ): List<Video> = coroutineScope {
        val audio =
            data.audio ?: when {
                links.audioLanguages.size > 1 -> "Dual"
                links.audioLanguages.size == 1 -> links.audioLanguages.first()
                else -> null
            }
        val info = listOfNotNull(audio, links.fileSize).joinToString(" · ")

        fun title(server: String) = buildString {
            append(data.resolution).append("p · ").append(server)
            if (info.isNotEmpty()) append(" · ").append(info)
        }
        val subtitles = links.subtitles.map { Track(it.first, it.second) }

        val torrentJob =
            links.torrent?.takeIf { showTorrent }?.let { url ->
                async { torrentVideo(url, title(SERVER_TORRENT), data) }
            }

        val videos = mutableListOf<Video>()

        fun add(
            url: String?,
            server: String,
        ) {
            if (url.isNullOrBlank() || videos.any { it.videoUrl == url }) return
            videos +=
                Video(
                    videoUrl = url,
                    videoTitle = title(server),
                    resolution = data.resolution,
                    headers = videoHeaders(url),
                    subtitleTracks = subtitles,
                    initialized = true,
                )
        }
        add(links.stream, SERVER_STREAM)
        add(links.dashUrl(), SERVER_DASH)
        add(links.download, SERVER_DIRECT)
        torrentJob?.await()?.let(videos::add)
        videos
    }

    // ============================== Torrent ===============================

    private suspend fun torrentVideo(
        url: String,
        title: String,
        data: HosterData,
    ): Video {
        if (url.startsWith("magnet:")) {
            return Video(videoUrl = url, videoTitle = title, resolution = data.resolution, initialized = true)
        }
        val magnet = withTimeoutOrNull(TORRENT_TIMEOUT_MS) { runCatching { torrentToMagnet(url) }.getOrNull() }
        return Video(
            videoUrl = magnet ?: url,
            videoTitle = title,
            resolution = data.resolution,
            internalData = if (magnet == null) TORRENT_PREFIX + url else "",
            initialized = magnet != null,
        )
    }

    /** Resolves a torrent that could not be converted while listing (e.g. a slow tracker site). */
    override suspend fun resolveVideo(video: Video): Video? {
        if (video.initialized || !video.internalData.startsWith(TORRENT_PREFIX)) return video
        val magnet =
            runCatching { torrentToMagnet(video.internalData.removePrefix(TORRENT_PREFIX)) }.getOrNull()
                ?: return null
        return video.copy(videoUrl = magnet, internalData = "", initialized = true)
    }

    private suspend fun torrentToMagnet(url: String): String {
        val response = client.get(url, videoHeaders(url), ensureSuccess = false)
        val bytes = response.use { if (it.isSuccessful) it.body.bytes() else ByteArray(0) }
        return runCatching { TorrentMagnet.fromTorrent(bytes) }.getOrElse {
            // Fall back to the app's torrent server, which fetches and indexes the file itself.
            withContext(Dispatchers.IO) {
                val torrent = TorrentUtils.getTorrentInfo(url, "AV1Encodes")
                val trackers = torrent.trackers.filter { it.isNotBlank() }.joinToString("") { "&tr=$it" }
                "magnet:?xt=urn:btih:${torrent.hash}&dn=${torrent.hash}$trackers&index=1"
            }
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val server = prefServer
        val quality = prefQuality.toIntOrNull()
        return sortedWith(
            compareByDescending<Video> { it.resolution == quality }
                .thenByDescending { it.videoTitle.contains(" · $server") }
                .thenBy { video ->
                    SERVER_ORDER.indexOfFirst { video.videoTitle.contains(" · $it") }.let {
                        if (it <
                            0
                        ) {
                            SERVER_ORDER.size
                        } else {
                            it
                        }
                    }
                },
        )
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_MIRROR_KEY,
            default = PREF_MIRROR_DEFAULT,
            title = "Mirror",
            summary = "%s\nIf the selected domain is down, the other mirrors are tried automatically",
            entries = listOf("av1encodes.com", "av1please.com", "animealpha.cc", "Custom domain"),
            entryValues = MIRRORS + MIRROR_CUSTOM,
        )
        screen.addEditTextPreference(
            key = PREF_CUSTOM_KEY,
            default = "",
            title = "Custom domain",
            summary = "Used when Mirror is set to \"Custom domain\" (e.g. https://new-mirror.example)",
            getSummary = { it.ifBlank { "Used when Mirror is set to \"Custom domain\" (e.g. https://new-mirror.example)" } },
            validate = { it.isBlank() || it.trim().toHttpUrlOrNull() != null },
            validationMessage = { "Enter a full address starting with https://" },
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = SERVER_ORDER,
            entryValues = SERVER_ORDER,
        )
        screen.addSwitchPreference(
            key = PREF_TORRENT_KEY,
            default = PREF_TORRENT_DEFAULT,
            title = "Show torrent links",
            summary = "Adds the Torrent server (streams through the app's torrent engine)",
        )
        screen.addSwitchPreference(
            key = PREF_COVERS_KEY,
            default = PREF_COVERS_DEFAULT,
            title = "Load covers in A–Z lists",
            summary = "The catalog pages have no posters; fetch them from each title's page",
        )
    }

    // =============================== Utils ================================

    private fun absolute(link: String): String = when {
        link.startsWith("magnet:") || link.startsWith("http://") || link.startsWith("https://") -> link
        link.startsWith("//") -> "https:$link"
        link.startsWith("/") -> baseUrl + link
        else -> "$baseUrl/$link"
    }

    /** "/download/...?token=..." for a site link given as absolute URL or path. */
    private fun sitePath(href: String): String? {
        if (href.isBlank()) return null
        if (!href.startsWith("http")) return "/" + href.trimStart('/')
        val url = href.toHttpUrlOrNull() ?: return null
        return url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
    }

    private fun String.toSiteUrl(): String {
        val url = toHttpUrlOrNull() ?: return absolute(this)
        if (url.host !in mirrorHosts()) return this
        return url
            .newBuilder()
            .host(baseUrl.toHttpUrl().host)
            .build()
            .toString()
    }

    private fun extractDdlToken(html: String): String? = DDL_TOKEN_REGEXES.firstNotNullOfOrNull { regex ->
        regex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.length >= 10 }
    }

    private fun extractWatchLink(html: String): String? = WATCH_REGEX
        .find(html)
        ?.value
        ?.replace("&amp;", "&")
        ?.replace("\\/", "/")
        ?.let(::absolute)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private class EpisodeRef(
        val slug: String,
        val season: String,
        val resolution: String,
        val fileName: String,
    ) {
        val number: Float? =
            EPISODE_REGEX
                .find(fileName)
                ?.groupValues
                ?.get(1)
                ?.toFloatOrNull()
        val audio: String? = AUDIO_REGEX.find(fileName)?.groupValues?.get(1)
        val normalizedName: String = normalizeFileName(fileName)

        companion object {
            /** Parses "/download/<slug>/<season>/<resolution>/<file>[?token=...]". */
            fun parse(path: String): EpisodeRef? {
                val segments = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
                if (segments.size < 5 || segments[0] != "download") return null
                return EpisodeRef(
                    slug = decode(segments[1]),
                    season = decode(segments[2]),
                    resolution = decode(segments[3]),
                    fileName = decode(segments.drop(4).joinToString("/")),
                )
            }
        }
    }

    companion object {
        private val MIRRORS = listOf("https://av1encodes.com", "https://av1please.com", "https://animealpha.cc")
        private const val MIRROR_CUSTOM = "custom"

        private const val PREF_MIRROR_KEY = "pref_mirror"
        private val PREF_MIRROR_DEFAULT = MIRRORS.first()
        private const val PREF_CUSTOM_KEY = "pref_custom_domain"
        private const val PREF_QUALITY_KEY = "pref_quality_v3"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_TORRENT_KEY = "pref_show_torrent_v3"
        private const val PREF_TORRENT_DEFAULT = true
        private const val PREF_COVERS_KEY = "pref_load_covers"
        private const val PREF_COVERS_DEFAULT = true

        private const val SERVER_STREAM = "Stream"
        private const val SERVER_DASH = "DASH"
        private const val SERVER_DIRECT = "Direct DL"
        private const val SERVER_TORRENT = "Torrent"
        private val SERVER_ORDER = listOf(SERVER_STREAM, SERVER_DASH, SERVER_DIRECT, SERVER_TORRENT)
        private const val PREF_SERVER_DEFAULT = SERVER_STREAM

        private const val TORRENT_PREFIX = "torrent:"
        private const val TORRENT_TIMEOUT_MS = 8_000L
        private const val RATE_LIMIT_PERMITS = 8
        private const val DETAIL_TTL_MS = 2 * 60 * 1000L
        private const val DETAIL_CACHE_SIZE = 48
        private const val LISTING_TTL_MS = 3 * 60 * 1000L
        private const val LISTING_CACHE_SIZE = 64

        private const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        private val STANDARD_RESOLUTIONS =
            linkedMapOf(
                1080 to "1920 x 1080",
                720 to "1280 x 720",
                480 to "854 x 480",
                360 to "640 x 360",
            )

        private const val CARD_SELECTOR =
            "article, li, .anime-card, .spotlight-slide, .similar-anime-card, .mini-item, .anime-item, [class*=card]"
        private const val TITLE_SELECTOR = ".spotlight-title, .similar-anime-title, h1, h2, h3, h4, .title, .anime-title"
        private val IGNORED_TITLES = setOf("view more", "watch now", "watch", "details", "more info", "a-z list", "anime")
        private val DETAIL_SKIP_KEYS = listOf("Genre", "Genres", "Studio", "Studios", "Status")

        private val EPISODE_REGEX = Regex("""\[(?:S\d+-)?E(\d+(?:\.\d+)?)]""", RegexOption.IGNORE_CASE)
        private val AUDIO_REGEX = Regex("""\[(Sub|Dual|Dub|Multi)]""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_TAG_REGEX = Regex("""\[\d{3,4}p]""", RegexOption.IGNORE_CASE)
        private val LABEL_NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")
        private val EPISODE_ONLY_LABEL = Regex("""^(?:Episode|Ep\.?)\s*\d+(?:\.\d+)?$""", RegexOption.IGNORE_CASE)
        private val HEIGHT_REGEX = Regex("""(\d{3,4})\s*p?\s*$""")
        private val PAGE_FILENAME_REGEX = Regex("""(?:encodedFilename|fileName|filename)\s*=\s*["']([^"']+\.(?:mkv|mp4))["']""")
        private val WATCH_REGEX = Regex("""(?:https?:\\?/\\?/[^\s"'<>]+)?\\?/watch\\?/[^\s"'<>\\]+""")
        private val DDL_TOKEN_REGEXES =
            listOf(
                Regex("""['"]X-DDL-Token['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
                Regex("""ddl[_-]?token['"\s]*[=:]\s*['"]([A-Za-z0-9+/=_.\-]+)['"]""", RegexOption.IGNORE_CASE),
                Regex("""['"](A{4,}[A-Za-z0-9_\-]{10,})['"]"""),
            )

        private fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(value)

        private fun encodeComponent(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun normalizeFileName(name: String): String = name
            .replace(RESOLUTION_TAG_REGEX, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()

        private fun heightOf(value: String): Int? = HEIGHT_REGEX
            .find(value.trim())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: Regex("""(\d{3,4})p""")
                .find(value)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        private fun slugOf(path: String): String = path.substringBefore('?').trimEnd('/').substringAfterLast('/')

        private fun seasonLabel(season: String): String = if (season.toIntOrNull() !=
            null
        ) {
            "Season $season"
        } else {
            season.replaceFirstChar { it.uppercase() }
        }

        private fun formatNumber(number: Float): String = if (number % 1f == 0f) number.toInt().toString() else number.toString()
    }
}
