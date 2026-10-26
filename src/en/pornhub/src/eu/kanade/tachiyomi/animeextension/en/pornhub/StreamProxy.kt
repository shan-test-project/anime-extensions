package eu.kanade.tachiyomi.animeextension.en.pornhub

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Loopback HTTP server that sits between the player and the CDN.
 *
 * MP4: the CDN caps the lower qualities at ~500 KB/s *per request* after a ~1.3 MB burst. The proxy
 * downloads the file in 1 MiB ranges over several parallel connections (each range stays inside the
 * burst) and streams them to the player in order, so playback starts and seeks almost instantly.
 *
 * HLS: playlists are rewritten so segments come through the proxy; the next segments are fetched in
 * parallel ahead of the player. Expired / refused links (403/404/410) are refreshed automatically by
 * re-reading the video page, so a dead CDN node no longer leaves the player spinning.
 */
class StreamProxy(baseClient: OkHttpClient) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .apply { interceptors().removeAll { it.javaClass.name.contains("Cloudflare", ignoreCase = true) } }
        .cache(null)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private class Mp4Entry(
        @Volatile var url: String,
        val headers: Headers,
        val refresh: suspend () -> String?,
    ) {
        @Volatile var size: Long = -1L

        @Volatile var head: Future<ByteArray>? = null

        @Volatile var lastRefresh = 0L
    }

    private class HlsEntry(
        @Volatile var masterUrl: String,
        val headers: Headers,
        val refresh: suspend () -> String?,
    ) {
        @Volatile var segments: List<String> = emptyList()

        @Volatile var lastRefresh = 0L
        val segmentCache: MutableMap<String, Future<ByteArray>> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, Future<ByteArray>>(16, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Future<ByteArray>>?) = size > SEGMENT_CACHE
                },
            )
    }

    private val mp4Entries = ConcurrentHashMap<String, Mp4Entry>()
    private val hlsEntries = ConcurrentHashMap<String, HlsEntry>()

    private val acceptPool = Executors.newCachedThreadPool(daemon("ph-proxy"))
    private val fetchPool = Executors.newFixedThreadPool(FETCH_THREADS, daemon("ph-fetch"))

    @Volatile private var server: ServerSocket? = null

    // ============================== Public API ==============================

    fun registerMp4(key: String, url: String, headers: Headers, refresh: suspend () -> String?): String {
        val existing = mp4Entries[key]
        if (existing == null || existing.url != url) {
            mp4Entries[key] = Mp4Entry(url, headers, refresh)
        }
        return "http://127.0.0.1:${ensureServer()}/mp4/$key.mp4"
    }

    fun registerHls(key: String, url: String, headers: Headers, refresh: suspend () -> String?): String {
        val existing = hlsEntries[key]
        if (existing == null || existing.masterUrl != url) {
            hlsEntries[key] = HlsEntry(url, headers, refresh)
        }
        return "http://127.0.0.1:${ensureServer()}/hls/$key/master.m3u8"
    }

    /** Start fetching the first bytes / playlists of a stream before the player asks for them. */
    fun prewarm(key: String) {
        mp4Entries[key]?.let { entry ->
            fetchPool.execute { runCatching { headChunk(entry).get(30, TimeUnit.SECONDS) } }
        }
        hlsEntries[key]?.let { entry ->
            fetchPool.execute {
                runCatching {
                    val variant = fetchMaster(entry).second.firstOrNull() ?: return@runCatching
                    fetchVariant(entry, variant)
                    entry.segments.take(PREWARM_SEGMENTS).forEach { segmentFuture(entry, it) }
                }
            }
        }
    }

    // ============================== Server ==============================

    @Synchronized
    private fun ensureServer(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        acceptPool.execute {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                acceptPool.execute { handle(client) }
            }
        }
        return socket.localPort
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.tcpNoDelay = true
                sock.soTimeout = 60_000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val target = parts.getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")
                val output = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
                val segs = path.trim('/').split('/')
                when {
                    segs.size == 2 && segs[0] == "mp4" ->
                        serveMp4(segs[1].substringBefore('.'), method, headers["range"], output)
                    segs.size == 3 && segs[0] == "hls" ->
                        serveHls(segs[1], segs[2], queryParam(query, "u"), method, output)
                    else -> writeSimple(output, 404, "text/plain", "Not found".toByteArray())
                }
                output.flush()
            } catch (_: Exception) {
                // Player closed the connection (seek / stop) or upstream failed; nothing to do.
            }
        }
    }

    // ============================== MP4 ==============================

    private fun serveMp4(key: String, method: String, rangeHeader: String?, out: OutputStream) {
        val entry = mp4Entries[key] ?: return writeSimple(out, 404, "text/plain", "Unknown stream".toByteArray())

        val head = runCatching { headChunk(entry).get(40, TimeUnit.SECONDS) }.getOrNull()
        val size = entry.size
        if (head == null || size <= 0) return writeSimple(out, 502, "text/plain", "Upstream failed".toByteArray())

        var start = 0L
        var end = size - 1
        val range = rangeHeader?.let { RANGE_REGEX.find(it) }
        if (range != null) {
            val a = range.groupValues[1]
            val b = range.groupValues[2]
            if (a.isEmpty() && b.isNotEmpty()) {
                start = (size - b.toLong()).coerceAtLeast(0)
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
            }
        }
        if (start >= size || start > end) {
            val msg = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(msg.toByteArray(Charsets.ISO_8859_1))
            return
        }

        val sb = StringBuilder()
        sb.append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: video/mp4\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: ${end - start + 1}\r\n")
        if (range != null) sb.append("Content-Range: bytes $start-$end/$size\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (method == "HEAD") return

        var next = start
        // Serve what the prewarmed head chunk already covers.
        if (start < head.size) {
            val stop = minOf(head.size.toLong() - 1, end)
            out.write(head, start.toInt(), (stop - start + 1).toInt())
            out.flush()
            next = stop + 1
        }
        if (next > end) return

        val window = ArrayDeque<Future<ByteArray>>()
        var first = true
        fun submitNext() {
            if (next > end) return
            val a = next
            val chunk = if (first) FIRST_CHUNK else CHUNK
            first = false
            val b = minOf(end, a + chunk - 1)
            next = b + 1
            window.addLast(fetchPool.submit<ByteArray> { fetchRange(entry, a, b) })
        }
        try {
            repeat(PARALLEL) { submitNext() }
            while (window.isNotEmpty()) {
                val bytes = window.removeFirst().get(60, TimeUnit.SECONDS)
                out.write(bytes)
                out.flush()
                submitNext()
            }
        } finally {
            window.forEach { it.cancel(true) }
        }
    }

    @Synchronized
    private fun headChunk(entry: Mp4Entry): Future<ByteArray> {
        entry.head?.let { existing ->
            if (!existing.isDone) return existing
            if (runCatching { existing.get() }.isSuccess) return existing
        }
        return fetchPool.submit<ByteArray> { fetchRange(entry, 0, HEAD_CHUNK - 1L) }.also { entry.head = it }
    }

    private fun fetchRange(entry: Mp4Entry, from: Long, to: Long): ByteArray {
        var lastError: Exception? = null
        repeat(4) { attempt ->
            try {
                val request = Request.Builder()
                    .url(entry.url)
                    .headers(entry.headers)
                    .header("Range", "bytes=$from-$to")
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 206 -> {
                            if (entry.size <= 0) {
                                response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { entry.size = it }
                            }
                            return response.body.bytes()
                        }
                        response.code == 200 -> {
                            // Range ignored: slice what we need.
                            val all = response.body.bytes()
                            if (entry.size <= 0) entry.size = all.size.toLong()
                            val endIdx = minOf(all.size.toLong() - 1, to).toInt()
                            return all.copyOfRange(from.toInt(), endIdx + 1)
                        }
                        response.code in REFRESH_CODES -> refreshMp4(entry)
                        else -> throw IOException("HTTP ${response.code}")
                    }
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt >= 1) Thread.sleep(300L * attempt)
            }
        }
        throw lastError ?: IOException("Failed to fetch range")
    }

    private fun refreshMp4(entry: Mp4Entry) {
        synchronized(entry) {
            if (System.currentTimeMillis() - entry.lastRefresh < 3_000) return
            val fresh = runCatching { runBlocking { entry.refresh() } }.getOrNull()
            entry.lastRefresh = System.currentTimeMillis()
            if (!fresh.isNullOrBlank()) entry.url = fresh
        }
    }

    // ============================== HLS ==============================

    private fun serveHls(key: String, action: String, target: String?, method: String, out: OutputStream) {
        val entry = hlsEntries[key] ?: return writeSimple(out, 404, "text/plain", "Unknown stream".toByteArray())
        when (action) {
            "master.m3u8" -> {
                val (text, _) = fetchMaster(entry)
                writeSimple(out, 200, M3U8_TYPE, text.toByteArray(), method == "HEAD")
            }
            "pl" -> {
                val url = target ?: return writeSimple(out, 400, "text/plain", ByteArray(0))
                writeSimple(out, 200, M3U8_TYPE, fetchVariant(entry, url).toByteArray(), method == "HEAD")
            }
            "seg" -> {
                val url = target ?: return writeSimple(out, 400, "text/plain", ByteArray(0))
                val index = entry.segments.indexOf(url)
                val bytes = segmentFuture(entry, url).let { future ->
                    runCatching { future.get(60, TimeUnit.SECONDS) }.getOrElse {
                        entry.segmentCache.remove(url)
                        throw it
                    }
                }
                if (index >= 0) {
                    entry.segments.drop(index + 1).take(PREFETCH_SEGMENTS).forEach { segmentFuture(entry, it) }
                }
                val type = if (url.substringBefore('?').endsWith(".ts")) "video/mp2t" else "application/octet-stream"
                writeSimple(out, 200, type, bytes, method == "HEAD")
            }
            else -> writeSimple(out, 404, "text/plain", ByteArray(0))
        }
    }

    /** Returns the rewritten master playlist and the absolute URLs of its variant playlists. */
    private fun fetchMaster(entry: HlsEntry): Pair<String, List<String>> {
        var text = getText(entry.masterUrl, entry.headers)
        if (text == null) {
            refreshHls(entry)
            text = getText(entry.masterUrl, entry.headers)
        }
        if (text == null) {
            refreshHls(entry, force = true)
            text = getText(entry.masterUrl, entry.headers) ?: throw IOException("Master playlist unavailable")
        }
        val base = entry.masterUrl
        val variants = mutableListOf<String>()
        val key = keyOf(entry)
        val rewritten = rewrite(text, base) { abs, isPlaylist ->
            if (isPlaylist) {
                variants += abs
                "/hls/$key/pl?u=${enc(abs)}"
            } else {
                "/hls/$key/seg?u=${enc(abs)}"
            }
        }
        return rewritten to variants
    }

    private fun fetchVariant(entry: HlsEntry, url: String): String {
        var variantUrl = url
        var text = getText(variantUrl, entry.headers)
        if (text == null) {
            // Token expired or node refused: get a fresh master and use its (single) variant.
            refreshHls(entry, force = true)
            variantUrl = runCatching { fetchMaster(entry).second.firstOrNull() }.getOrNull() ?: throw IOException("No variant")
            text = getText(variantUrl, entry.headers) ?: throw IOException("Variant playlist unavailable")
        }
        val key = keyOf(entry)
        val segments = mutableListOf<String>()
        val rewritten = rewrite(text, variantUrl) { abs, isPlaylist ->
            if (isPlaylist) {
                "/hls/$key/pl?u=${enc(abs)}"
            } else {
                segments += abs
                "/hls/$key/seg?u=${enc(abs)}"
            }
        }
        val old = entry.segments
        entry.segments = segments
        // Requests for old (expired) segment URLs map to the same position in the new list.
        if (old.isNotEmpty() && old != segments) remap.putAll(old.zip(segments).toMap())
        return rewritten
    }

    private val remap = ConcurrentHashMap<String, String>()

    private fun segmentFuture(entry: HlsEntry, url: String): Future<ByteArray> {
        synchronized(entry.segmentCache) {
            entry.segmentCache[url]?.let { return it }
            val future = fetchPool.submit<ByteArray> { fetchSegment(entry, url) }
            entry.segmentCache[url] = future
            return future
        }
    }

    private fun fetchSegment(entry: HlsEntry, original: String): ByteArray {
        var url = remap[original] ?: original
        var lastError: Exception? = null
        repeat(4) { attempt ->
            try {
                val request = Request.Builder().url(url).headers(entry.headers).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body.bytes()
                    if (response.code in REFRESH_CODES) {
                        val index = entry.segments.indexOf(url).takeIf { it >= 0 }
                        refreshHls(entry, force = true)
                        runCatching {
                            fetchMaster(entry).second.firstOrNull()?.let { fetchVariant(entry, it) }
                        }
                        url = remap[original] ?: index?.let { entry.segments.getOrNull(it) } ?: url
                    }
                    throw IOException("HTTP ${response.code}")
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt >= 1) Thread.sleep(300L * attempt)
            }
        }
        throw lastError ?: IOException("Segment failed")
    }

    private fun refreshHls(entry: HlsEntry, force: Boolean = false) {
        synchronized(entry) {
            val since = System.currentTimeMillis() - entry.lastRefresh
            if (since < if (force) 3_000 else 10_000) return
            val fresh = runCatching { runBlocking { entry.refresh() } }.getOrNull()
            entry.lastRefresh = System.currentTimeMillis()
            if (!fresh.isNullOrBlank()) entry.masterUrl = fresh
        }
    }

    private fun keyOf(entry: HlsEntry): String = hlsEntries.entries.firstOrNull { it.value === entry }?.key ?: ""

    private fun getText(url: String, headers: Headers): String? {
        repeat(2) {
            val result = runCatching {
                val request = Request.Builder().url(url).headers(headers).build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> response.body.string()
                        response.code in REFRESH_CODES -> return null
                        else -> throw IOException("HTTP ${response.code}")
                    }
                }
            }
            result.getOrNull()?.let { if (it.trimStart().startsWith("#EXTM3U")) return it }
        }
        return null
    }

    private fun rewrite(text: String, base: String, mapper: (String, Boolean) -> String): String {
        val baseUrl = base.toHttpUrlOrNull()
        var nextIsPlaylist = false
        return text.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> raw
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) nextIsPlaylist = true
                    URI_ATTR.replace(raw) { match ->
                        val abs = baseUrl?.resolve(match.groupValues[1])?.toString() ?: match.groupValues[1]
                        val isPlaylist = line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                        "URI=\"${mapper(abs, isPlaylist)}\""
                    }
                }
                else -> {
                    val abs = baseUrl?.resolve(line)?.toString() ?: line
                    val isPlaylist = nextIsPlaylist || abs.substringBefore('?').endsWith(".m3u8")
                    nextIsPlaylist = false
                    mapper(abs, isPlaylist)
                }
            }
        }
    }

    // ============================== Helpers ==============================

    private fun writeSimple(out: OutputStream, code: Int, type: String, body: ByteArray, headOnly: Boolean = false) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Bad Gateway"
        }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(b.toChar())
            if (buffer.length > 16_384) return buffer.toString()
        }
    }

    private fun queryParam(query: String, name: String): String? = query.split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val PARALLEL = 6
        private const val FETCH_THREADS = 16
        private const val HEAD_CHUNK = 512 * 1024
        private const val FIRST_CHUNK = 512 * 1024L
        private const val CHUNK = 1024 * 1024L
        private const val PREFETCH_SEGMENTS = 4
        private const val PREWARM_SEGMENTS = 2
        private const val SEGMENT_CACHE = 10
        private const val M3U8_TYPE = "application/vnd.apple.mpegurl"
        private val REFRESH_CODES = setOf(403, 404, 410)
        private val RANGE_REGEX = Regex("""bytes=(\d*)-(\d*)""")
        private val URI_ATTR = Regex("""URI="([^"]+)"""")

        private fun daemon(name: String) = ThreadFactory { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }
    }
}
