package eu.kanade.tachiyomi.animeextension.en.anipm

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/**
 * Ani.pm publishes the supported catalog values at https://ani.pm/genres.
 *
 * Genre, tag, studio, year, season, format, and status remain text filters on
 * purpose: the site can add values without requiring an extension rebuild, and
 * the source passes the entered value through unchanged to Ani.pm's browse API.
 */
object AniPmFilters {

    class SortFilter : AnimeFilter.Select<String>("Sort", arrayOf("Trending", "Popular", "Latest")) {
        fun value(): String = arrayOf("trending", "popular", "recent")[state]
    }

    class TextFilter(
        displayName: String,
        val queryKey: String,
    ) : AnimeFilter.Text(displayName)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            AnimeFilter.Header("Values follow Ani.pm's /genres page; enter the current site value."),
            SortFilter(),
            TextFilter("General genre", "genre"),
            TextFilter("Tag", "tag"),
            TextFilter("Studio", "studio"),
            TextFilter("Year", "year"),
            TextFilter("Season", "season"),
            TextFilter("Format", "format"),
            TextFilter("Status", "status"),
        )
}
