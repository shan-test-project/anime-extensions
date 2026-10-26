package eu.kanade.tachiyomi.animeextension.en.pornhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object PornhubFilters {

    open class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
        state: Int = 0,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray(), state) {
        val selected: String get() = options[state].second
    }

    class OrientationFilter : SelectFilter("Orientation", ORIENTATIONS)
    class SortFilter : SelectFilter("Sort by", SORTS)
    class PeriodFilter : SelectFilter("Period (Most Viewed / Top Rated)", PERIODS)
    class CategoryFilter : SelectFilter("Category (browse only)", CATEGORIES)
    class ProductionFilter : SelectFilter("Production", PRODUCTIONS)
    class DurationFilter : SelectFilter("Duration", DURATIONS)
    class HdFilter : AnimeFilter.CheckBox("HD only")

    /** Pornstar list is fetched from the site; until it arrives only a placeholder is shown. */
    class PornstarFilter(stars: List<Pair<String, String>>) :
        SelectFilter(
            "Pornstar",
            if (stars.isEmpty()) {
                arrayOf(Pair("Tap Reset to load pornstars", ""))
            } else {
                arrayOf(Pair("Any", "")) + stars.toTypedArray()
            },
        )

    fun getFilterList(pornstars: List<Pair<String, String>> = emptyList()) = AnimeFilterList(
        AnimeFilter.Header("Tip: paste a Pornhub video, model, pornstar or channel link into search to open it"),
        OrientationFilter(),
        SortFilter(),
        PeriodFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Category applies when the search text is empty. Pornstar works with or without search text."),
        CategoryFilter(),
        PornstarFilter(pornstars),
        ProductionFilter(),
        DurationFilter(),
        HdFilter(),
    )

    class Params(
        val orientation: String,
        val sort: String,
        val period: String,
        val category: String,
        val production: String,
        val duration: String,
        val hd: Boolean,
        val pornstar: String = "",
    ) {
        val isDefault: Boolean
            get() = orientation.isEmpty() && sort.isEmpty() && period.isEmpty() && category.isEmpty() &&
                production.isEmpty() && duration.isEmpty() && !hd && pornstar.isEmpty()
    }

    fun parse(filters: AnimeFilterList): Params {
        fun <T : SelectFilter> find(cls: Class<T>): String = filters.filterIsInstance(cls).firstOrNull()?.selected.orEmpty()
        return Params(
            orientation = find(OrientationFilter::class.java),
            sort = find(SortFilter::class.java),
            period = find(PeriodFilter::class.java),
            category = find(CategoryFilter::class.java),
            production = find(ProductionFilter::class.java),
            duration = find(DurationFilter::class.java),
            hd = filters.filterIsInstance<HdFilter>().firstOrNull()?.state ?: false,
            pornstar = find(PornstarFilter::class.java),
        )
    }

    private val ORIENTATIONS = arrayOf(
        Pair("Straight", ""),
        Pair("Gay", "gay"),
        Pair("Transgender", "transgender"),
    )

    /** Sort keys; "new" maps to `cm` when browsing and `mr` when searching. */
    private val SORTS = arrayOf(
        Pair("Default (Featured / Relevance)", ""),
        Pair("Hottest", "ht"),
        Pair("Most Viewed", "mv"),
        Pair("Top Rated", "tr"),
        Pair("Newest", "new"),
        Pair("Longest", "lg"),
    )

    private val PERIODS = arrayOf(
        Pair("Default", ""),
        Pair("Today", "t"),
        Pair("This Week", "w"),
        Pair("This Month", "m"),
        Pair("This Year", "y"),
        Pair("All Time", "a"),
    )

    private val PRODUCTIONS = arrayOf(
        Pair("All", ""),
        Pair("Professional", "professional"),
        Pair("Homemade", "homemade"),
    )

    private val DURATIONS = arrayOf(
        Pair("Any", ""),
        Pair("Short videos (under 10 min)", "max_duration=10"),
        Pair("10+ min", "min_duration=10"),
        Pair("20+ min", "min_duration=20"),
        Pair("30+ min", "min_duration=30"),
        Pair("10-20 min", "min_duration=10&max_duration=20"),
    )

    /** Value is either a numeric `c=` category id or a dedicated listing path. */
    private val CATEGORIES = arrayOf(
        Pair("All", ""),
        Pair("18-25", "/categories/teen"),
        Pair("60FPS", "105"),
        Pair("Amateur", "3"),
        Pair("Anal", "35"),
        Pair("Arab", "98"),
        Pair("Asian", "1"),
        Pair("Babe", "/categories/babe"),
        Pair("Babysitter (18+)", "89"),
        Pair("BBW", "6"),
        Pair("Behind The Scenes", "141"),
        Pair("Big Ass", "4"),
        Pair("Big Dick", "7"),
        Pair("Big Tits", "8"),
        Pair("Bisexual Male", "76"),
        Pair("Blonde", "9"),
        Pair("Blowjob", "13"),
        Pair("Bondage", "10"),
        Pair("Brazilian", "102"),
        Pair("British", "96"),
        Pair("Brunette", "11"),
        Pair("Bukkake", "14"),
        Pair("Cartoon", "86"),
        Pair("Casting", "90"),
        Pair("Celebrity", "12"),
        Pair("Closed Captions", "732"),
        Pair("College (18+)", "/categories/college"),
        Pair("Compilation", "57"),
        Pair("Cosplay", "241"),
        Pair("Creampie", "15"),
        Pair("Cuckold", "242"),
        Pair("Cumshot", "16"),
        Pair("Czech", "100"),
        Pair("Double Penetration", "72"),
        Pair("Ebony", "17"),
        Pair("Euro", "55"),
        Pair("Exclusive", "115"),
        Pair("Feet", "93"),
        Pair("Female Orgasm", "502"),
        Pair("Fetish", "18"),
        Pair("Fingering", "592"),
        Pair("Fisting", "19"),
        Pair("French", "94"),
        Pair("Funny", "32"),
        Pair("Gaming", "881"),
        Pair("Gangbang", "80"),
        Pair("German", "95"),
        Pair("Handjob", "20"),
        Pair("Hardcore", "21"),
        Pair("HD Porn", "/hd"),
        Pair("Hentai", "/categories/hentai"),
        Pair("Indian", "101"),
        Pair("Interactive", "/interactive"),
        Pair("Interracial", "25"),
        Pair("Italian", "97"),
        Pair("Japanese", "111"),
        Pair("Korean", "103"),
        Pair("Latina", "26"),
        Pair("Lesbian", "27"),
        Pair("Massage", "78"),
        Pair("Masturbation", "22"),
        Pair("Mature", "28"),
        Pair("MILF", "29"),
        Pair("Muscular Men", "512"),
        Pair("Music", "121"),
        Pair("Old/Young (18+)", "181"),
        Pair("Orgy", "2"),
        Pair("Parody", "201"),
        Pair("Party", "53"),
        Pair("Pissing", "211"),
        Pair("Podcast", "891"),
        Pair("Popular With Women", "/popularwithwomen"),
        Pair("Pornstar", "/categories/pornstar"),
        Pair("POV", "41"),
        Pair("Public", "24"),
        Pair("Pussy Licking", "131"),
        Pair("Reality", "31"),
        Pair("Red Head", "42"),
        Pair("Role Play", "81"),
        Pair("Romantic", "522"),
        Pair("Rough Sex", "67"),
        Pair("Russian", "99"),
        Pair("School (18+)", "88"),
        Pair("Small Tits", "59"),
        Pair("Smoking", "91"),
        Pair("Solo Female", "492"),
        Pair("Solo Male", "92"),
        Pair("Squirt", "69"),
        Pair("Step Fantasy", "444"),
        Pair("Strap On", "542"),
        Pair("Striptease", "33"),
        Pair("Tattooed Women", "562"),
        Pair("Threesome", "65"),
        Pair("Toys", "23"),
        Pair("Verified Amateurs", "138"),
        Pair("Verified Couples", "482"),
        Pair("Verified Models", "139"),
        Pair("Vintage", "43"),
        Pair("Virtual Reality", "/vr"),
        Pair("Webcam", "61"),
    )
}
