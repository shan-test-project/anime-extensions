package eu.kanade.tachiyomi.animeextension.en.pornhub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS resolver for the site and its CDN.
 *
 * Many ISPs block or poison DNS answers for the site, which makes every request hang until the
 * system resolver times out. Resolving through Cloudflare / Google over HTTPS (contacted by IP, so
 * no DNS lookup is needed to reach them) avoids that, and answers are cached for their TTL.
 */
class DohDns(baseClient: OkHttpClient, private val enabled: () -> Boolean) : Dns {

    private val dohClient = baseClient.newBuilder()
        .dns(Dns.SYSTEM)
        .cache(null)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private class Cached(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (!enabled() || !TARGET_HOSTS.any { hostname == it || hostname.endsWith(".$it") }) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { it.expiresAt > now }?.let { return it.addresses }

        for (provider in PROVIDERS) {
            val result = runCatching { query(provider, hostname) }.getOrNull()
            if (!result.isNullOrEmpty()) {
                val ttl = result.minOf { it.second }.coerceIn(60, 3600)
                val addresses = result.map { it.first }
                cache[hostname] = Cached(addresses, now + ttl * 1000L)
                return addresses
            }
        }
        // Stale answer beats no answer; otherwise fall back to the system resolver.
        cache[hostname]?.let { return it.addresses }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun query(provider: String, hostname: String): List<Pair<InetAddress, Int>> {
        val request = Request.Builder()
            .url("$provider?name=$hostname&type=A")
            .header("Accept", "application/dns-json")
            .build()
        val body = dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body.string()
        }
        val answer = json.decodeFromString<DohResponse>(body)
        if (answer.status != 0) return emptyList()
        return answer.answer.orEmpty()
            .filter { it.type == 1 && IPV4_REGEX.matches(it.data) }
            .map { InetAddress.getByAddress(hostname, it.data.split('.').map { part -> part.toInt().toByte() }.toByteArray()) to it.ttl }
    }

    @Serializable
    private class DohResponse(
        @SerialName("Status") val status: Int = -1,
        @SerialName("Answer") val answer: List<DohAnswer>? = null,
    )

    @Serializable
    private class DohAnswer(
        val type: Int = 0,
        @SerialName("TTL") val ttl: Int = 300,
        val data: String = "",
    )

    companion object {
        private val PROVIDERS = listOf(
            "https://1.1.1.1/dns-query",
            "https://8.8.8.8/resolve",
            "https://1.0.0.1/dns-query",
        )
        private val TARGET_HOSTS = listOf("pornhub.com", "phncdn.com", "pornhub.org")
        private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
