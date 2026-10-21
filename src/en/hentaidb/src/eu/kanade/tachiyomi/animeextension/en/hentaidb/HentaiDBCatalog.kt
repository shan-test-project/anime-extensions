package eu.kanade.tachiyomi.animeextension.en.hentaidb

import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory port of the site's own browse logic (`/js/query.*.js`): the same filters,
 * sort orders and search scoring the website applies to its catalog.
 */
class HentaiDBCatalog(dto: CatalogDto) {

    val tags: List<String> = dto.tags

    private val studioRaw: List<String> = dto.studios

    private val tagMigrations: Map<String, String> = dto.mig?.t.orEmpty()

    val entries: List<Entry>

    val bySlug: Map<String, Entry>

    /** Number of titles using each tag index. */
    private val tagUses: IntArray = IntArray(tags.size)

    /** Folded studio key -> display name. */
    private val studioNames = LinkedHashMap<String, String>()

    /** Display studio name -> number of titles. */
    private val studioCounts = HashMap<String, Int>()

    private val tagWords: List<List<String>> = tags.map { tag ->
        tag.lowercase(Locale.ROOT).split(NON_ALNUM_REGEX).filter(String::isNotEmpty)
    }

    private val tagIndex: Map<String, Int> = tags.withIndex().associate { it.value to it.index }

    private val foldedTags = HashMap<String, Int>()

    private val collator: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }

    private val orders = ConcurrentHashMap<String, List<Entry>>()

    init {
        entries = dto.rows.map { row ->
            val studio = row.st?.let { studioRaw.getOrNull(it) }.orEmpty()
            Entry(row, studio, row.g.mapNotNull { tags.getOrNull(it) })
        }
        entries.forEach { entry ->
            entry.row.g.forEach { if (it in tagUses.indices) tagUses[it]++ }
            entry.studioDisplayParts.forEachIndexed { i, part ->
                val key = entry.studioParts[i]
                if (key.isEmpty()) return@forEachIndexed
                val name = studioNames.getOrPut(key) { part }
                studioCounts[name] = (studioCounts[name] ?: 0) + 1
            }
        }
        bySlug = entries.associateBy { it.row.s }

        tags.forEachIndexed { i, tag ->
            val key = fold(tag)
            if (key.isEmpty()) return@forEachIndexed
            val current = foldedTags[key]
            if (current == null || tagUses[i] > tagUses[current]) foldedTags[key] = i
        }
        tagMigrations.forEach { (from, to) ->
            val key = fold(from)
            val target = tagIndex[to]
            if (key.isNotEmpty() && key !in foldedTags && target != null) foldedTags[key] = target
        }
    }

    /** Tags ordered by how many titles use them. */
    val tagsByUsage: List<String>
        get() = tags.indices.filter { tagUses[it] > 0 }.sortedByDescending { tagUses[it] }.map { tags[it] }

    /** Studios ordered by title count, as (key, display name). */
    val studiosByUsage: List<Pair<String, String>>
        get() = studioNames.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, String>> { studioCounts[it.value] ?: 0 }
                    .thenComparator { a, b -> collator.compare(a.value, b.value) },
            )
            .map { it.key to it.value }

    fun resolveTag(name: String): Int? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        tagIndex[trimmed]?.let { return it }
        tagMigrations[trimmed]?.let { migrated -> tagIndex[migrated]?.let { return it } }
        return foldedTags[fold(trimmed)]
    }

    fun resolveStudio(value: String): String? {
        val key = fold(value)
        if (key.isEmpty()) return null
        if (key in studioNames) return key
        val byCount = compareByDescending<String> { studioCounts[studioNames[it]] ?: 0 }
        return studioNames.keys.filter { it.startsWith(key) }.sortedWith(byCount).firstOrNull()
            ?: studioNames.keys.filter { it.indexOf(key) > 0 }.sortedWith(byCount).firstOrNull()
    }

    // ============================== Querying ==============================

    fun search(query: Query): List<Entry> {
        val words = query.words.map { it.lowercase(Locale.ROOT) }.filter(String::isNotEmpty)
        val ordered = order(query.sort, query.reversed)
        if (words.isEmpty()) return ordered.filter { matches(it, query) }

        return ordered.asSequence()
            .filter { matches(it, query) }
            .mapNotNull { entry ->
                var score = 0
                for (word in words) {
                    val s = termScore(entry, word)
                    if (s == 0) return@mapNotNull null
                    score += s
                }
                entry to score
            }
            .toList()
            .sortedByDescending { it.second } // stable: keeps the selected order for ties
            .map { it.first }
    }

    private fun matches(r: Entry, n: Query): Boolean {
        val row = r.row
        if (n.minQuality > 0 && row.q < n.minQuality) return false
        if (n.subtitles > 0 && row.u < n.subtitles) return false
        if (n.cut != 0 && row.c != n.cut) return false
        if (n.fps48 && row.f48 == 0) return false
        if (n.status != 0 && row.sa != n.status) return false
        if (n.type != 0 && row.ty != n.type) return false
        when (n.episodes) {
            1 -> if (row.e != 1) return false
            2 -> if (row.e !in 2..4) return false
            5 -> if (row.e < 5) return false
        }
        when (n.length) {
            "short" -> if (!(r.median > 0 && r.median < 600)) return false
            "mid" -> if (!(r.median >= 600 && r.median <= 1200)) return false
            "long" -> if (!(r.median > 1200)) return false
        }
        if (n.dub && row.dub == 0) return false
        if (n.studio.isNotEmpty() && n.studio !in r.studioParts) return false
        if (n.includedTags.any { it !in row.g }) return false
        if (n.excludedTags.any { it in row.g }) return false
        return true
    }

    private fun termScore(r: Entry, t: String): Int {
        val title = r.titleLower
        val i = title.indexOf(t)
        return when {
            title == t -> 100
            i == 0 -> 70
            title.contains(" $t") -> 50
            i > 0 -> 35
            r.studioWords.contains(" $t") -> 25
            " ${r.row.k}".lowercase(Locale.ROOT).contains(" $t") -> 25
            r.row.g.any { k -> tagWords.getOrNull(k)?.any { it.startsWith(t) } == true } -> 15
            else -> 0
        }
    }

    private fun order(sort: String, reversed: Boolean): List<Entry> {
        val key = if (sort in SORT_KEYS) sort else "pop"
        val id = key + if (reversed) "-" else ""
        return orders.getOrPut(id) {
            val m = if (reversed) -1 else 1
            val unknown: (Entry) -> Boolean = when (key) {
                "pop" -> { e -> e.row.p == 0 }
                "runtime" -> { e -> e.runtime == 0 }
                "eplen" -> { e -> e.median == 0.0 }
                "quality" -> { e -> e.row.q == 0 }
                "subs" -> { e -> e.row.ul == 0 }
                "year" -> { e -> e.row.y == 0 }
                "studio" -> { e -> e.studio.isEmpty() }
                else -> { _ -> false }
            }
            val compare: (Entry, Entry) -> Int = when (key) {
                "title" -> { a, b -> collator.compare(a.row.t, b.row.t) }
                "eps" -> { a, b -> b.row.e.compareTo(a.row.e) }
                "runtime" -> { a, b -> b.runtime.compareTo(a.runtime) }
                "eplen" -> { a, b -> b.median.compareTo(a.median) }
                "quality" -> { a, b -> b.row.q.compareTo(a.row.q).takeIf { it != 0 } ?: b.row.f48.compareTo(a.row.f48) }
                "subs" -> { a, b -> b.row.ul.compareTo(a.row.ul).takeIf { it != 0 } ?: b.row.u.compareTo(a.row.u) }
                "year" -> { a, b -> b.row.y.compareTo(a.row.y) }
                "studio" -> { a, b -> collator.compare(a.studio, b.studio) }
                else -> { a, b -> a.row.p.compareTo(b.row.p) }
            }
            entries.sortedWith { a, b ->
                val ua = unknown(a)
                val ub = unknown(b)
                when {
                    ua != ub -> if (ua) 1 else -1
                    else -> (m * compare(a, b)).takeIf { it != 0 }
                        ?: collator.compare(a.row.t, b.row.t).takeIf { it != 0 }
                        ?: a.row.s.compareTo(b.row.s)
                }
            }
        }
    }

    class Entry(val row: CatalogRowDto, val studio: String, val tagNames: List<String>) {
        val studioDisplayParts: List<String> = studio.split(STUDIO_SPLIT_REGEX).map(String::trim).filter(String::isNotEmpty)
        val studioParts: List<String> = studioDisplayParts.map { fold(it) }
        val studioWords: String = studioDisplayParts.joinToString(" ").lowercase(Locale.ROOT).let { joined ->
            " " + joined.replace(NON_ALNUM_REGEX, " ") + " " + joined
        }
        val titleLower: String = row.t.lowercase(Locale.ROOT)
        private val known: List<Int> = row.d.filter { it > 0 }.sorted()
        val runtime: Int = if (row.d.isNotEmpty() && known.size == row.d.size) known.sum() else 0
        val median: Double = when {
            known.isEmpty() -> 0.0
            known.size % 2 == 1 -> known[known.size / 2].toDouble()
            else -> (known[known.size / 2 - 1] + known[known.size / 2]) / 2.0
        }
    }

    class Query(
        val words: List<String> = emptyList(),
        val sort: String = "pop",
        val reversed: Boolean = false,
        val minQuality: Int = 0,
        val subtitles: Int = 0,
        val cut: Int = 0,
        val fps48: Boolean = false,
        val status: Int = 0,
        val type: Int = 0,
        val episodes: Int = 0,
        val length: String = "",
        val dub: Boolean = false,
        val studio: String = "",
        val includedTags: List<Int> = emptyList(),
        val excludedTags: List<Int> = emptyList(),
    )

    companion object {
        val SORT_KEYS = listOf("pop", "title", "year", "eps", "runtime", "eplen", "quality", "subs", "studio")
        val TYPES = listOf("OVA", "Movie", "TV Series", "Web", "Other")
        val STATUSES = listOf("Completed", "Ongoing", "Stalled")

        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
        private val STUDIO_SPLIT_REGEX = Regex("\\s*,\\s*")

        fun fold(value: String): String = value.lowercase(Locale.ROOT).replace(NON_ALNUM_REGEX, "")
    }
}
