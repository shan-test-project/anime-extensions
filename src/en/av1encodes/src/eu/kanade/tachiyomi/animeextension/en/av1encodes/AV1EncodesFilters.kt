package eu.kanade.tachiyomi.animeextension.en.av1encodes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AV1EncodesFilters {
    open class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected: String get() = options.getOrNull(state)?.second.orEmpty()
    }

    class ListFilter : SelectFilter("Browse", LISTS)

    class SortFilter : SelectFilter("Sort by", SORTS)

    class LetterFilter : SelectFilter("Starts with", LETTERS)

    class YearFromFilter : SelectFilter("Year from", years())

    class YearToFilter : SelectFilter("Year to", years())

    class GenreCheckBox(
        name: String,
        val value: String,
    ) : AnimeFilter.CheckBox(name)

    class GenreFilter : AnimeFilter.Group<GenreCheckBox>("Genres", GENRES.map { GenreCheckBox(it.first, it.second) })

    fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Text search ignores the filters below"),
        ListFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Catalog options (Browse: Full catalog)"),
        SortFilter(),
        LetterFilter(),
        GenreFilter(),
        YearFromFilter(),
        YearToFilter(),
    )

    data class Params(
        val list: String = "",
        val sort: String = "",
        val letter: String = "",
        val genres: List<String> = emptyList(),
        val yearFrom: String = "",
        val yearTo: String = "",
    )

    fun parse(filters: AnimeFilterList): Params {
        var params = Params()
        filters.forEach { filter ->
            params =
                when (filter) {
                    is ListFilter -> params.copy(list = filter.selected)
                    is SortFilter -> params.copy(sort = filter.selected)
                    is LetterFilter -> params.copy(letter = filter.selected)
                    is YearFromFilter -> params.copy(yearFrom = filter.selected)
                    is YearToFilter -> params.copy(yearTo = filter.selected)
                    is GenreFilter -> params.copy(genres = filter.state.filter { it.state }.map { it.value })
                    else -> params
                }
        }
        return params
    }

    private val LISTS =
        listOf(
            "Full catalog" to "",
            "Airing now (Sub)" to "airing/sub",
            "Airing now (Dual audio)" to "airing/dual",
        )

    private val SORTS =
        listOf(
            "Latest added" to "latest",
            "A–Z" to "a-z",
            "Z–A" to "z-a",
            "Episode count" to "episodes",
        )

    private val LETTERS =
        listOf("Any" to "") +
            ('A'..'Z').map { it.toString() to it.toString() } +
            listOf("#" to "0-9")

    private val GENRES =
        listOf(
            "Action" to "action",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Horror" to "horror",
            "Romance" to "romance",
            "Sci-Fi" to "sci-fi",
        )

    private fun years(): List<Pair<String, String>> {
        val current = Calendar.getInstance().get(Calendar.YEAR)
        return listOf("Any" to "") + (current downTo 1970).map { it.toString() to it.toString() }
    }
}
