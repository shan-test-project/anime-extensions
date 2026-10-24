package eu.kanade.tachiyomi.animeextension.en.av1encodes

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Turns the bytes of a .torrent file into a magnet link the app's torrent
 * server can stream. The info-hash is the SHA-1 of the raw bencoded `info`
 * dictionary, so the file is walked byte-exactly instead of re-encoded.
 */
internal object TorrentMagnet {
    private const val END = 'e'.code.toByte()

    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "webm", "avi", "mov", "m4v", "ts")

    fun fromTorrent(
        bytes: ByteArray,
        extraTrackers: List<String> = emptyList(),
    ): String {
        require(bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte()) { "Not a torrent file" }
        val reader = Reader(bytes)
        reader.pos = 1

        var infoStart = -1
        var infoEnd = -1
        var info: Map<*, *>? = null
        val trackers = mutableListOf<String>()

        while (bytes[reader.pos] != END) {
            when (reader.readString().decodeToString()) {
                "info" -> {
                    infoStart = reader.pos
                    info = reader.readValue() as? Map<*, *>
                    infoEnd = reader.pos
                }
                "announce" -> (reader.readValue() as? ByteArray)?.let { trackers += it.decodeToString() }
                "announce-list" ->
                    (reader.readValue() as? List<*>)?.forEach { tier ->
                        (tier as? List<*>)?.forEach { url -> (url as? ByteArray)?.let { trackers += it.decodeToString() } }
                    }
                else -> reader.readValue()
            }
        }
        require(infoStart >= 0 && info != null) { "Torrent has no info dictionary" }

        val hash =
            MessageDigest
                .getInstance("SHA-1")
                .digest(bytes.copyOfRange(infoStart, infoEnd))
                .joinToString("") { "%02x".format(it) }
        val name = (info["name"] as? ByteArray)?.decodeToString()

        return buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            if (!name.isNullOrBlank()) append("&dn=").append(encode(name))
            (trackers + extraTrackers)
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { append("&tr=").append(encode(it)) }
            append("&index=").append(fileIndex(info))
        }
    }

    /** 1-based index (torrent server convention) of the main video file. */
    private fun fileIndex(info: Map<*, *>): Int {
        val files = info["files"] as? List<*> ?: return 1
        val entries =
            files.mapIndexedNotNull { index, file ->
                val map = file as? Map<*, *> ?: return@mapIndexedNotNull null
                val length = map["length"] as? Long ?: 0L
                val path = (map["path"] as? List<*>)?.mapNotNull { (it as? ByteArray)?.decodeToString() }.orEmpty()
                Triple(index + 1, length, path.lastOrNull().orEmpty())
            }
        val videos = entries.filter { it.third.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS }
        return (videos.ifEmpty { entries }).maxByOrNull { it.second }?.first ?: 1
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private class Reader(
        private val bytes: ByteArray,
    ) {
        var pos = 0

        fun readValue(): Any? = when (bytes[pos].toInt().toChar()) {
            'd' -> {
                pos++
                val map = LinkedHashMap<String, Any?>()
                while (bytes[pos] != END) {
                    val key = readString().decodeToString()
                    map[key] = readValue()
                }
                pos++
                map
            }
            'l' -> {
                pos++
                val list = mutableListOf<Any?>()
                while (bytes[pos] != END) list += readValue()
                pos++
                list
            }
            'i' -> {
                pos++
                val end = indexOf('e')
                val value = String(bytes, pos, end - pos, Charsets.US_ASCII).toLong()
                pos = end + 1
                value
            }
            in '0'..'9' -> readString()
            else -> throw IllegalArgumentException("Invalid bencode at byte $pos")
        }

        fun readString(): ByteArray {
            val colon = indexOf(':')
            val length = String(bytes, pos, colon - pos, Charsets.US_ASCII).toInt()
            val start = colon + 1
            require(start + length <= bytes.size) { "Truncated torrent file" }
            pos = start + length
            return bytes.copyOfRange(start, start + length)
        }

        private fun indexOf(char: Char): Int {
            val target = char.code.toByte()
            var i = pos
            while (i < bytes.size) {
                if (bytes[i] == target) return i
                i++
            }
            throw IllegalArgumentException("Truncated torrent file")
        }
    }
}
