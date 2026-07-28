package eu.kanade.tachiyomi.animeextension.en.aniwaves

import aniyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AniWaves : ZoroTheme(
    lang = "en",
    name = "AniWaves",
    baseUrl = "https://aniwaves.ru",
    hosterNames = listOf("HD-1", "HD-2", "StreamTape", "VidCloud", "HD-Mix"),
) {

    private val megaCloudExtractor by lazy {
        MegaCloudExtractor(client, headers, "https://megacloud.blog")
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending?page=$page", docHeaders)

    override fun popularAnimeSelector(): String = "a.item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst(".title.d-title, .name.d-title")?.let {
            it.attr("data-jp").ifBlank { it.text() }
        } ?: element.attr("href").substringAfterLast("/")
        thumbnail_url = element.selectFirst(".poster img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item a.page-link:contains(›)"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/newest?page=$page", docHeaders)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    // aniwaves.ru uses /filter for both keyword search and filter browsing
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = ZoroThemeFilters.getSearchParameters(filters)
        val url = "$baseUrl/filter".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("keyword", query)
            if (params.type.isNotBlank()) addQueryParameter("type", params.type)
            if (params.status.isNotBlank()) addQueryParameter("status", params.status)
            if (params.rated.isNotBlank()) addQueryParameter("rated", params.rated)
            if (params.score.isNotBlank()) addQueryParameter("score", params.score)
            if (params.season.isNotBlank()) addQueryParameter("season", params.season)
            if (params.language.isNotBlank()) addQueryParameter("language", params.language)
            if (params.sort.isNotBlank()) addQueryParameter("sort", params.sort)
            if (params.genres.isNotBlank()) addQueryParameter("genre[]", params.genres)
        }.build()
        return GET(url.toString(), docHeaders)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst(".binfo .poster img")?.attr("src")

        title = document.selectFirst("h1.title.d-title")?.let {
            it.attr("data-jp").ifBlank { it.text() }
        } ?: ""

        val bmeta = document.selectFirst(".bmeta")

        genre = bmeta?.select("a[href^=/genre/]")?.eachText()?.joinToString(", ")

        author = bmeta?.select("a[href^=/studio/]")?.eachText()?.joinToString(", ")

        // Status is determined by the link to /ongoing or /completed
        status = when {
            bmeta?.selectFirst("a[href=/ongoing]") != null -> SAnime.ONGOING
            bmeta?.selectFirst("a[href=/completed]") != null -> SAnime.COMPLETED
            bmeta?.text()?.contains("Airing", ignoreCase = true) == true -> SAnime.ONGOING
            bmeta?.text()?.contains("Finished Airing", ignoreCase = true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        description = buildString {
            document.selectFirst(".synopsis .text.content")?.text()?.let { append(it) }

            bmeta?.let { meta ->
                meta.selectFirst("div:contains(Premiered:) span")?.text()
                    ?.takeIf { it.isNotBlank() }?.let { append("\n\nPremiered: $it") }

                meta.selectFirst("div:contains(Date aired:) span")?.text()
                    ?.takeIf { it.isNotBlank() }?.let { append("\n\nAired: $it") }

                meta.selectFirst("div:contains(Status:) span")?.text()
                    ?.takeIf { it.isNotBlank() }?.let { append("\n\nStatus: $it") }
            }

            document.selectFirst(".names.font-italic")?.text()
                ?.takeIf { it.isNotBlank() }
                ?.let { append("\n\nAlternate titles: $it") }
        }.trim()
    }

    // ============================ Video Links =============================

    override suspend fun extractVideo(server: VideoData): List<Video> {
        if (server.link.isBlank()) return emptyList()
        return try {
            when {
                server.link.contains("megacloud", ignoreCase = true) ||
                    server.link.contains("rapid-cloud", ignoreCase = true) ||
                    server.link.contains("vidcloud", ignoreCase = true) ->
                    megaCloudExtractor.getVideosFromUrl(
                        server.link,
                        server.type,
                        server.name,
                    )
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
