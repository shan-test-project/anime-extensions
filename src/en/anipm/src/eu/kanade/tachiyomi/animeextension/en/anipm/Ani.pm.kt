package eu.kanade.tachiyomi.animeextension.en.anipm

import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class AniPm : AnimeHttpSource() {

    override val name = "Ani.pm"
    override val lang = "en"
    override val baseUrl = "https://ani.pm"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api/anime"
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private fun apiHeaders(referer: String = "$baseUrl/"): Headers = headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Referer", referer)
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .build()

    private fun mediaHeaders(referer: String): Headers = headersBuilder()
        .set("Accept", "*/*")
        .set("Referer", referer)
        .set("Origin", baseUrl)
        .set("Sec-Fetch-Site", "cross-site")
        .build()

    // ============================== Popular / Trending ==============================

    override fun popularAnimeRequest(page: Int): Request = GET(
        "$apiUrl/browse?sort=trending&page=$page",
        apiHeaders("$baseUrl/anime"),
    )

    override fun popularAnimeParse(response: Response): AnimesPage = parsePage(response)

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$apiUrl/latest-episodes?page=$page",
        apiHeaders("$baseUrl/anime"),
    )

    override fun latestUpdatesParse(response: Response): AnimesPage = parsePage(response)

    // ============================== Search / Filters ==============================

    override fun getFilterList(): AnimeFilterList = AniPmFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val builder = if (query.isNotBlank()) {
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
        } else {
            "$apiUrl/browse".toHttpUrl().newBuilder()
        }

        builder.addQueryParameter("page", page.toString())
        filters.filterIsInstance<AniPmFilters.SortFilter>().firstOrNull()
            ?.value()?.takeIf(String::isNotBlank)
            ?.let { builder.addQueryParameter("sort", it) }
        filters.filterIsInstance<AniPmFilters.TextFilter>().forEach { filter ->
            filter.state.trim().takeIf(String::isNotBlank)
                ?.let { builder.addQueryParameter(filter.queryKey, it) }
        }

        return GET(builder.build(), apiHeaders("$baseUrl/anime"))
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parsePage(response)

    // ============================== Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(
        "$baseUrl${anime.url}",
        apiHeaders("$baseUrl/anime"),
    )

    override fun animeDetailsParse(response: Response): SAnime = parseAnime(JSONObject(response.bodyString()))

    // ============================== Related ==============================

    override fun relatedAnimeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun relatedAnimeListParse(response: Response): List<SAnime> =
        relatedFrom(JSONObject(response.bodyString()))

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        return parseEpisodes(JSONObject(response.bodyString()))
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> =
        parseEpisodes(JSONObject(response.bodyString()))

    // ============================== Playback ==============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split(EPISODE_SEPARATOR)
        if (parts.size != 3) return emptyList()

        val titleRoute = parts[0]
        val episodeRoute = parts[1]
        val episodeNumber = parts[2]
        val bootstrapUrl = "$apiUrl/playback-bootstrap/settlar/$titleRoute".toHttpUrl()
            .newBuilder()
            .addQueryParameter("ep", episodeRoute)
            .addQueryParameter("lang", "sub")
            .build()
        val bootstrap = JSONObject(
            client.newCall(GET(bootstrapUrl, apiHeaders("$baseUrl/anime"))).awaitSuccess().bodyString(),
        )
        val selection = bootstrap.string("settlarSelection").takeIf(String::isNotBlank)
            ?: return emptyList()
        val effectiveLanguage = bootstrap.string("effectiveLanguage").ifBlank { "sub" }
        val actualEpisode = bootstrap.optInt("episode", episodeNumber.toIntOrNull() ?: 0)
        val sessionUrl = "$apiUrl/settlar/session".toHttpUrl().newBuilder()
            .addQueryParameter("selection", selection)
            .addQueryParameter("provider", "anipm")
            .addQueryParameter("ep", actualEpisode.toString())
            .addQueryParameter("channel", if (effectiveLanguage == "dub") "dub" else "sub")
            .addQueryParameter("telemetry", "0")
            .build()
        val session = JSONObject(
            client.newCall(GET(sessionUrl, apiHeaders("$baseUrl/anime"))).awaitSuccess().bodyString(),
        )
        val embedUrl = session.string("embedUrl").takeIf(String::isNotBlank) ?: return emptyList()
        return universalExtractor.videosFromUrl(
            embedUrl,
            mediaHeaders(embedUrl),
            prefix = "$name - Episode $episodeNumber",
        )
    }

    private fun parsePage(response: Response): AnimesPage {
        val root = JSONObject(response.bodyString())
        val items = root.optJSONArray("items") ?: root.optJSONArray("results") ?: JSONArray()
        val animes = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val anime = item.optJSONObject("anime") ?: item
                animeToSAnime(anime)?.let(::add)
            }
        }.distinctBy { it.url }
        val page = root.optInt("page", response.request.url.queryParameter("page")?.toIntOrNull() ?: 1)
        val lastPage = root.optInt("lastPage", page)
        val hasNext = root.optBoolean("hasNext", root.optBoolean("hasNextPage", false)) ||
            (lastPage > page) || animes.size >= PAGE_SIZE
        return AnimesPage(animes, hasNext)
    }

    private fun parseAnime(root: JSONObject): SAnime = SAnime.create().apply {
        setUrlWithoutDomain("/api/anime/series/${root.string("routeId")}")
        title = root.string("title")
        thumbnail_url = imageUrl(root.string("poster"))
        description = root.string("synopsis").takeIf(String::isNotBlank)
        genre = buildList {
            root.stringArray("genres").let(::addAll)
            root.stringArray("tags").let(::addAll)
        }.distinct().joinToString().takeIf(String::isNotBlank)
        author = root.stringArray("studios").joinToString().takeIf(String::isNotBlank)
        status = statusOf(root.string("status"))
    }

    private fun animeToSAnime(root: JSONObject): SAnime? {
        val routeId = root.string("routeId").takeIf(String::isNotBlank) ?: return null
        return SAnime.create().apply {
            setUrlWithoutDomain("/api/anime/series/$routeId")
            title = root.string("title")
            thumbnail_url = imageUrl(root.string("poster"))
            status = statusOf(root.string("status"))
        }.takeIf { it.title.isNotBlank() }
    }

    private fun parseEpisodes(root: JSONObject): List<SEpisode> {
        val titleRoute = root.string("routeId").takeIf(String::isNotBlank) ?: return emptyList()
        val episodes = root.optJSONArray("episodes") ?: return emptyList()
        return buildList {
            for (index in 0 until episodes.length()) {
                val item = episodes.optJSONObject(index) ?: continue
                val routeId = item.string("routeId").takeIf(String::isNotBlank) ?: continue
                val number = item.optInt("number", item.optInt("sourceNumber", 0))
                if (number <= 0) continue
                add(SEpisode.create().apply {
                    url = listOf(titleRoute, routeId, number.toString()).joinToString(EPISODE_SEPARATOR)
                    name = item.string("title").ifBlank { "Episode $number" }
                    episode_number = number.toFloat()
                    date_upload = item.string("aired").toLongDate()
                })
            }
        }.sortedByDescending { it.episode_number }
    }

    private fun relatedFrom(root: JSONObject): List<SAnime> {
        val output = mutableListOf<SAnime>()
        listOf("relations", "recommendations").forEach { key ->
            val values = root.optJSONArray(key) ?: return@forEach
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                animeToSAnime(item)?.let { anime -> output += anime }
            }
        }
        return output.distinctBy { it.url }
    }

    private fun imageUrl(value: String): String? = value.takeIf(String::isNotBlank)?.let {
        if (it.startsWith("http")) it else "$baseUrl${if (it.startsWith("/")) it else "/$it"}"
    }

    private fun statusOf(value: String): Int = when {
        value.contains("airing", ignoreCase = true) -> SAnime.ONGOING
        value.contains("finished", ignoreCase = true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun JSONObject.string(key: String): String = optString(key).takeIf {
        it.isNotBlank() && it != "null"
    }.orEmpty()

    private fun JSONObject.stringArray(key: String): List<String> {
        val values = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                values.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun String.toLongDate(): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(this)?.time ?: 0L
    }.getOrDefault(0L)

    companion object {
        private const val EPISODE_SEPARATOR = "|"
        private const val PAGE_SIZE = 20
    }
}
