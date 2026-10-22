package eu.kanade.tachiyomi.animeextension.en.hentaidb

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object HentaiDBFilters {

    // (label, catalog sort key, whether "ascending" is the site's default direction)
    private val SORTS = listOf(
        Triple("Popularity", "pop", false),
        Triple("Title", "title", true),
        Triple("Listed year", "year", false),
        Triple("Episodes", "eps", false),
        Triple("Total runtime", "runtime", false),
        Triple("Episode length", "eplen", false),
        Triple("Quality", "quality", false),
        Triple("Subtitles", "subs", false),
        Triple("Studio", "studio", true),
    )

    class SortFilter(state: Selection = Selection(0, false)) :
        AnimeFilter.Sort("Sort by", SORTS.map { it.first }.toTypedArray(), state)

    open class ValueSelect(name: String, private val options: List<Pair<String, String>>) :
        AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected: String get() = options.getOrNull(state)?.second.orEmpty()
    }

    class TypeFilter : ValueSelect("Type", listOf("Any" to "") + HentaiDBCatalog.TYPES.mapIndexed { i, t -> t to "${i + 1}" })
    class StatusFilter : ValueSelect("Status", listOf("Any" to "") + HentaiDBCatalog.STATUSES.mapIndexed { i, t -> t to "${i + 1}" })
    class CutFilter : ValueSelect("Cut", listOf("Any" to "", "Censored" to "1", "Uncensored" to "2"))
    class QualityFilter : ValueSelect("Quality", listOf("Any" to "", "720p or better" to "720", "1080p or better" to "1080", "4K" to "2160"))
    class SubtitleFilter : ValueSelect("Subtitles", listOf("Any" to "", "Any subtitles" to "1", "English subtitles" to "2"))
    class EpisodeCountFilter : ValueSelect("Episodes", listOf("Any" to "", "Single episode" to "1", "2 to 4 episodes" to "2", "5 or more episodes" to "5"))
    class EpisodeLengthFilter : ValueSelect("Episode length", listOf("Any" to "", "Under 10 min" to "short", "10 to 20 min" to "mid", "Over 20 min" to "long"))
    class StudioSelectFilter(studios: List<Pair<String, String>>) : ValueSelect("Studio", listOf("Any" to "") + studios.map { it.second to it.first })

    class DubFilter : AnimeFilter.CheckBox("English dub only")
    class Fps48Filter : AnimeFilter.CheckBox("48 fps copies only")

    class StudioTextFilter : AnimeFilter.Text("Studio name")
    class IncludeTagsTextFilter : AnimeFilter.Text("Include tags (comma separated)")
    class ExcludeTagsTextFilter : AnimeFilter.Text("Exclude tags (comma separated)")

    class TagFilter(name: String) : AnimeFilter.TriState(name)
    class TagGroup(tags: List<String>) : AnimeFilter.Group<TagFilter>("Tags", tags.map(::TagFilter))

    fun getFilterList(catalog: HentaiDBCatalog?): AnimeFilterList {
        val filters = mutableListOf<AnimeFilter<*>>(
            AnimeFilter.Header("Filters also apply to text searches"),
            SortFilter(),
            TypeFilter(),
            StatusFilter(),
            CutFilter(),
            QualityFilter(),
            SubtitleFilter(),
            EpisodeCountFilter(),
            EpisodeLengthFilter(),
            DubFilter(),
            Fps48Filter(),
            AnimeFilter.Separator(),
        )
        if (catalog != null) {
            filters += StudioSelectFilter(catalog.studiosByUsage)
            filters += TagGroup(catalog.tagsByUsage)
        } else {
            filters += AnimeFilter.Header("Browse once, then press Reset to load the studio and tag lists")
            filters += StudioTextFilter()
        }
        filters += AnimeFilter.Separator()
        filters += IncludeTagsTextFilter()
        filters += ExcludeTagsTextFilter()
        return AnimeFilterList(filters)
    }

    fun buildQuery(words: List<String>, filters: AnimeFilterList, catalog: HentaiDBCatalog): HentaiDBCatalog.Query {
        var sort = "pop"
        var reversed = false
        var type = 0
        var status = 0
        var cut = 0
        var quality = 0
        var subtitles = 0
        var episodes = 0
        var length = ""
        var dub = false
        var fps48 = false
        var studio = ""
        val included = linkedSetOf<Int>()
        val excluded = linkedSetOf<Int>()

        fun splitTags(text: String) = text.split(',').mapNotNull { catalog.resolveTag(it) }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> filter.state?.let { selection ->
                    val (_, key, defaultAscending) = SORTS.getOrElse(selection.index) { SORTS.first() }
                    sort = key
                    reversed = selection.ascending != defaultAscending
                }
                is TypeFilter -> type = filter.selected.toIntOrNull() ?: 0
                is StatusFilter -> status = filter.selected.toIntOrNull() ?: 0
                is CutFilter -> cut = filter.selected.toIntOrNull() ?: 0
                is QualityFilter -> quality = filter.selected.toIntOrNull() ?: 0
                is SubtitleFilter -> subtitles = filter.selected.toIntOrNull() ?: 0
                is EpisodeCountFilter -> episodes = filter.selected.toIntOrNull() ?: 0
                is EpisodeLengthFilter -> length = filter.selected
                is StudioSelectFilter -> if (filter.selected.isNotEmpty()) studio = filter.selected
                is StudioTextFilter -> if (filter.state.isNotBlank()) studio = catalog.resolveStudio(filter.state) ?: HentaiDBCatalog.fold(filter.state)
                is DubFilter -> dub = filter.state
                is Fps48Filter -> fps48 = filter.state
                is TagGroup -> filter.state.forEach { tag ->
                    val index = catalog.resolveTag(tag.name) ?: return@forEach
                    when {
                        tag.isIncluded() -> included += index
                        tag.isExcluded() -> excluded += index
                    }
                }
                is IncludeTagsTextFilter -> included += splitTags(filter.state)
                is ExcludeTagsTextFilter -> excluded += splitTags(filter.state)
                else -> {}
            }
        }
        included.removeAll(excluded)

        return HentaiDBCatalog.Query(
            words = words,
            sort = sort,
            reversed = reversed,
            minQuality = quality,
            subtitles = subtitles,
            cut = cut,
            fps48 = fps48,
            status = status,
            type = type,
            episodes = episodes,
            length = length,
            dub = dub,
            studio = studio,
            includedTags = included.toList(),
            excludedTags = excluded.toList(),
        )
    }
}
