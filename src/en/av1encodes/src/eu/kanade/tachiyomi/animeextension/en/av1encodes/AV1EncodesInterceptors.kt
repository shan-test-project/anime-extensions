package eu.kanade.tachiyomi.animeextension.en.av1encodes

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Keeps the site reachable:
 *  - mirror failover: when the selected domain answers with a Cloudflare
 *    tunnel/origin error (52x/530) or cannot be reached, the same path is
 *    retried on the other mirrors (they share one backend), and the working
 *    mirror is remembered for a while;
 *  - gateway block retry: the site's gateway sometimes redirects a fresh
 *    client to /home/blocked. Re-visiting the home page (which issues the
 *    av1_client_id identity cookie) and retrying gets through for normal
 *    connections.
 */
class SiteInterceptor(
    private val mirrorHosts: () -> List<String>,
) : Interceptor {
    @Volatile private var stickyHost: String? = null

    @Volatile private var stickyUntil = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val mirrors = mirrorHosts()
        if (original.url.host !in mirrors) return chain.proceed(original)

        val candidates =
            buildList {
                val sticky = stickyHost?.takeIf { System.currentTimeMillis() < stickyUntil && it in mirrors }
                if (sticky != null) add(sticky)
                add(original.url.host)
                addAll(mirrors)
            }.distinct()

        val canFailover = original.method == "GET"
        var lastError: IOException? = null

        for ((index, host) in candidates.withIndex()) {
            val isLast = !canFailover || index == candidates.lastIndex
            val request = if (host == original.url.host) original else original.withHost(host)
            val response =
                try {
                    chain.proceed(request)
                } catch (e: IOException) {
                    if (chain.call().isCanceled()) throw e
                    lastError = e
                    if (isLast) break else continue
                }

            if (response.code in MIRROR_DOWN_CODES && !isLast) {
                response.close()
                continue
            }

            if (host != original.url.host) {
                stickyHost = host
                stickyUntil = System.currentTimeMillis() + STICKY_MS
            } else if (host == stickyHost && response.isSuccessful) {
                stickyUntil = System.currentTimeMillis() + STICKY_MS
            }
            return retryWhenBlocked(chain, request, response)
        }

        throw lastError ?: IOException("AV1Encodes: all mirrors are unreachable right now")
    }

    private fun retryWhenBlocked(
        chain: Interceptor.Chain,
        request: Request,
        first: Response,
    ): Response {
        var response = first
        repeat(BLOCK_RETRIES) {
            if (!response.isBlockPage()) return response
            response.close()
            runCatching { chain.proceed(warmupRequest(request)).close() }
            response = chain.proceed(request)
        }
        if (response.isBlockPage()) {
            response.close()
            throw IOException(
                "AV1Encodes blocked this connection. The site blacklists VPN, proxy and datacenter IPs — " +
                    "disable VPN/DNS proxies, open the site once in WebView, then retry.",
            )
        }
        return response
    }

    private fun warmupRequest(request: Request): Request {
        val home =
            request.url
                .newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
        return request
            .newBuilder()
            .url(home)
            .get()
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .removeHeader("X-DDL-Token")
            .removeHeader("Referer")
            .build()
    }

    private fun Request.withHost(host: String): Request {
        val builder = newBuilder().url(url.newBuilder().host(host).build())
        header("Referer")?.let { builder.header("Referer", it.swapHost(host)) }
        header("Origin")?.let { builder.header("Origin", it.swapHost(host)) }
        return builder.build()
    }

    private fun String.swapHost(host: String): String {
        val url = toHttpUrlOrNull() ?: return this
        if (url.host !in mirrorHosts()) return this
        return url
            .newBuilder()
            .host(host)
            .build()
            .toString()
    }

    companion object {
        private val MIRROR_DOWN_CODES = setOf(502, 504, 520, 521, 522, 523, 524, 525, 526, 530)
        private const val BLOCK_RETRIES = 2
        private const val STICKY_MS = 10 * 60 * 1000L
        private const val BLOCK_PEEK_BYTES = 64L * 1024

        private val BLOCK_MARKERS = listOf("Connection blacklisted", "Access Blocked", "/home/blocked?ip=")

        fun Response.isBlockPage(): Boolean {
            if (request.url.encodedPath.startsWith("/home/blocked")) return true
            if (code != 200 && code != 403 && code != 429) return false
            if (header("Content-Type")?.contains("html", ignoreCase = true) != true) return false
            val head = runCatching { peekBody(BLOCK_PEEK_BYTES).string() }.getOrDefault("")
            return BLOCK_MARKERS.any { head.contains(it, ignoreCase = true) }
        }
    }
}

/**
 * Hands real Cloudflare challenges (managed challenge / Turnstile, e.g. on the
 * player pages) to the WebView solver from lib/cloudflareinterceptor. Other
 * responses — including the site's own block page — pass straight through, so
 * the WebView is only ever started when a challenge is actually served.
 */
class ChallengeInterceptor(
    private val solver: Interceptor,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isChallenge()) return response
        response.close()
        return solver.intercept(chain)
    }

    private fun Response.isChallenge(): Boolean {
        if (header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
        if (code != 403 && code != 503 && code != 429) return false
        if (header("Server")?.startsWith("cloudflare", ignoreCase = true) != true) return false
        val head = runCatching { peekBody(16L * 1024).string() }.getOrDefault("")
        return CHALLENGE_MARKERS.any { head.contains(it, ignoreCase = true) }
    }

    companion object {
        private val CHALLENGE_MARKERS = listOf("challenge-platform", "Just a moment", "cf-turnstile", "cf_chl_opt")
    }
}
