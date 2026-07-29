package eu.kanade.tachiyomi.animeextension.en.aniwaves

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AniWaves : ParsedAnimeHttpSource() {

    override val name = "AniWaves"
    override val baseUrl = "https://aniwaves.ru"
    override val lang = "en"
    override val supportsLatest = true

    // ============================= Headers ================================

    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "$baseUrl/")
            .build()
    }

    private fun docHeaders(referer: String = "$baseUrl/"): Headers =
        headers.newBuilder()
            .add(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            .add("Referer", referer)
            .build()

    // ============================= DTOs ==================================

    @Serializable
    private data class AjaxHtmlResponse(
        val status: Int = 0,
        val result: String = "",
    )

    @Serializable
    private data class SourcesApiResponse(
        val status: Int = 0,
        val result: SourceResult? = null,
    )

    @Serializable
    private data class SourceResult(
        val url: String = "",
    )

    // EchoVideo / MegaCloud sources API response
    @Serializable
    private data class EchoVideoSources(
        val sources: List<EchoVideoSource>? = null,
        val tracks: List<EchoVideoTrack>? = null,
        val encrypted: Boolean = false,
    )

    @Serializable
    private data class EchoVideoSource(
        val file: String = "",
        val type: String = "hls",
    )

    @Serializable
    private data class EchoVideoTrack(
        val file: String = "",
        val kind: String = "",
        val label: String = "",
    )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending?page=$page", docHeaders())

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
        GET("$baseUrl/newest?page=$page", docHeaders())

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/filter".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("keyword", query)
        }.build().toString()
        return GET(url, docHeaders())
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

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

        status = when {
            bmeta?.selectFirst("a[href=/ongoing]") != null -> SAnime.ONGOING
            bmeta?.selectFirst("a[href=/completed]") != null -> SAnime.COMPLETED
            bmeta?.text()?.contains("Airing", ignoreCase = true) == true -> SAnime.ONGOING
            bmeta?.text()?.contains("Finished", ignoreCase = true) == true -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        description = buildString {
            document.selectFirst(".synopsis .text.content")?.text()?.let { append(it) }
            bmeta?.selectFirst("div:contains(Premiered:) span")?.text()
                ?.takeIf { it.isNotBlank() }?.let { append("\n\nPremiered: $it") }
            document.selectFirst(".names.font-italic")?.text()
                ?.takeIf { it.isNotBlank() }?.let { append("\n\nAlternate titles: $it") }
        }.trim()
    }

    // ============================== Episodes ==============================
    //
    // API: GET /ajax/episode/list/{animeId}
    // Response: {"status":200, "result": "<html>"}
    // Ep elements: li a[data-num]  href="/watch/{id}/ep-{num}"

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("-")
        return GET("$baseUrl/ajax/episode/list/$id", apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = response.parseAs<AjaxHtmlResponse>().result
        val doc = Jsoup.parseBodyFragment(html)
        return doc.select("li a[data-num]").map { element ->
            SEpisode.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                episode_number = element.attr("data-num").toFloatOrNull() ?: 1F
                val epTitle = element.attr("title").ifBlank { "Episode ${element.attr("data-num")}" }
                name = "Ep. ${element.attr("data-num")}: $epTitle"
                date_upload = 0L
            }
        }.reversed()
    }

    // Unused — overridden by episodeListParse
    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    //
    // Flow:
    //   1. Reconstruct "servers" param from episode URL: /watch/{id}/ep-{n} → "{id}&eps={n}"
    //   2. GET /ajax/server/list?servers={id}&eps={n}
    //      Response: {"status":200,"result":"<html with .type[data-type] li[data-link-id]>"}
    //   3. For each server: GET /ajax/sources?id={link-id}&asi=0&autoPlay=0
    //      Response: {"status":200,"result":{"url":"https://play.echovideo.ru/embed-20/..."}}
    //   4. Extract video from the embed URL

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val epUrl = episode.url // e.g. /watch/81553/ep-1
        val animeId = epUrl.substringAfter("/watch/").substringBefore("/")
        val epNum = epUrl.substringAfterLast("ep-")
        val serversParam = "$animeId&eps=$epNum"

        val serverResponse = client.newCall(
            GET(
                "$baseUrl/ajax/server/list?servers=$serversParam",
                apiHeaders.newBuilder().set("Referer", baseUrl + epUrl).build(),
            ),
        ).await()

        val serverHtml = serverResponse.parseAs<AjaxHtmlResponse>().result
        val serverDoc = Jsoup.parseBodyFragment(serverHtml)

        // Map of CSS data-type → display label
        val typeLabels = mapOf("sub" to "SUB", "dub" to "DUB", "ssub" to "S-Sub", "raw" to "RAW")

        val servers = typeLabels.flatMap { (cssType, label) ->
            serverDoc.select("div.type[data-type=$cssType] li[data-link-id]").map { li ->
                Triple(li.attr("data-link-id"), li.text().trim(), label)
            }
        }

        return servers.parallelCatchingFlatMap { (linkId, serverName, serverType) ->
            fetchVideosFromLinkId(linkId, serverName, serverType, epUrl)
        }
    }

    private suspend fun fetchVideosFromLinkId(
        linkId: String,
        serverName: String,
        serverType: String,
        epUrl: String,
    ): List<Video> {
        val resp = client.newCall(
            GET(
                "$baseUrl/ajax/sources?id=$linkId&asi=0&autoPlay=0",
                apiHeaders.newBuilder().set("Referer", baseUrl + epUrl).build(),
            ),
        ).await().parseAs<SourcesApiResponse>()

        val embedUrl = resp.result?.url?.takeIf { it.isNotBlank() } ?: return emptyList()

        return try {
            extractVideos(embedUrl, serverName, serverType)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================ EchoVideo / MegaCloud extractor =========
    //
    // play.echovideo.ru uses JW Player with MegaCloud backend.
    // Embed page has: <div id="mg-player" data-id="..." data-realid="REALID">
    // Sources API: GET /embed-2/v3/e-1/getSources?id={REALID}
    // (Same API path as megacloud.blog, just different domain)

    private val ECHOVIDEO_SOURCES_PATH = "/embed-2/v3/e-1/getSources?id="

    private suspend fun extractVideos(
        embedUrl: String,
        serverName: String,
        serverType: String,
    ): List<Video> {
        val embedHost = embedUrl.toHttpUrl().host // e.g. play.echovideo.ru
        val embedHeaders = headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .build()

        // Fetch embed page to get data-realid
        val embedPage = client.newCall(GET(embedUrl, embedHeaders)).await().asJsoup()
        val realId = embedPage.selectFirst("#mg-player")?.attr("data-realid")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        // Fetch sources via MegaCloud-compatible API
        val sourcesHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Referer", "https://$embedHost/")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val sourcesUrl = "https://$embedHost$ECHOVIDEO_SOURCES_PATH$realId"
        val sourcesResp = client.newCall(GET(sourcesUrl, sourcesHeaders)).await()

        if (!sourcesResp.isSuccessful) return emptyList()

        val sourcesBody = sourcesResp.body.string()

        // Parse response (sources may be encrypted — try plain first)
        return try {
            val dto = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }.decodeFromString<EchoVideoSources>(sourcesBody)

            val subtitles = dto.tracks
                ?.filter { it.kind == "captions" }
                ?.map { Track(it.file, it.label) }
                .orEmpty()

            dto.sources.orEmpty().map { src ->
                Video(
                    src.file,
                    "$serverName [$serverType]",
                    src.file,
                    headers = sourcesHeaders,
                    subtitleTracks = subtitles,
                )
            }
        } catch (_: Exception) {
            // If encrypted or different format, try extracting m3u8 directly
            val m3u8 = Regex("""['"]?(https?://[^\s'"]+\.m3u8[^\s'"]*)['"]?""")
                .find(sourcesBody)?.groupValues?.getOrNull(1)
            if (m3u8 != null) {
                listOf(Video(m3u8, "$serverName [$serverType]", m3u8, headers = sourcesHeaders))
            } else {
                emptyList()
            }
        }
    }

    // ========================= Unsupported stubs ==========================

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}
