package eu.kanade.tachiyomi.extension.ko.ntk

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

inline fun <reified T> FilterList.firstInstanceOrNull(): T? = filterIsInstance<T>().firstOrNull()

data class FilterOption(val name: String, val value: String)

// --- SHARED ---
internal val sortList = listOf(
    FilterOption("최신순", "new"),
    FilterOption("북마크순", "bookmark"),
    FilterOption("조회순", "views"),
)

class GenreTriState(val genre: String) : Filter.TriState(genre)

// --- MANGA FILTERS ---
class SortFilter : Filter.Select<String>("정렬", sortList.map { it.name }.toTypedArray())
class StatusFilter : Filter.Select<String>("상태", statusList.map { it.name }.toTypedArray())
class GenreFilter : Filter.Group<GenreTriState>("장르", genreList.map { GenreTriState(it) })

internal val statusList = listOf(
    FilterOption("연재중", "ongoing"),
    FilterOption("완결", "end"),
)

internal val genreList = listOf(
    "순정", "판타지", "러브코미디", "드라마", "17", "학원", "라노벨", "개그", "액션", "백합", "SF",
    "일상", "이세계", "스릴러", "애니화", "전생", "스포츠", "TS", "소년", "먹방", "붕탁", "게임",
    "호러", "시대", "로맨스", "추리", "무협", "음악", "BL",
)

fun buildGenreParam(genreFilter: GenreFilter?): String? {
    if (genreFilter == null) return null
    val genres = genreFilter.state
        .filterIsInstance<GenreTriState>()
        .mapIndexedNotNull { index, triState ->
            when (triState.state) {
                Filter.TriState.STATE_INCLUDE -> genreList[index]
                Filter.TriState.STATE_EXCLUDE -> "-${genreList[index]}"
                else -> null
            }
        }
    return if (genres.isNotEmpty()) genres.joinToString(",") else null
}

// --- WEBTOON FILTERS ---
class WtSortFilter : Filter.Select<String>("정렬", sortList.map { it.name }.toTypedArray())
class WtStatusFilter : Filter.Select<String>("상태", wtStatusList.map { it.name }.toTypedArray())
class WtCategoryFilter : Filter.Select<String>("분류", wtCatList.map { it.name }.toTypedArray())
class WtDayFilter : Filter.Select<String>("요일", wtDayList.map { it.name }.toTypedArray())

internal val wtGenreList = listOf(
    Pair("학원", 1),
    Pair("액션", 2),
    Pair("SF", 3),
    Pair("스토리", 4),
    Pair("판타지", 5),
    Pair("BL", 6),
    Pair("개그", 7),
    Pair("연애", 8),
    Pair("드라마", 9),
    Pair("로맨스", 10),
    Pair("시대극", 11),
    Pair("스포츠", 12),
    Pair("일상", 13),
    Pair("추리", 14),
    Pair("공포", 15),
    Pair("성인", 16),
    Pair("옴니버스", 17),
    Pair("에피소드", 18),
    Pair("무협", 19),
    Pair("소년", 20),
    Pair("기타", 99),
)

class WtGenreFilter : Filter.Group<GenreTriState>("장르", wtGenreList.map { GenreTriState(it.first) })

internal val wtStatusList = listOf(
    FilterOption("연재중", "ing"),
    FilterOption("완결", "end"),
)

internal val wtCatList = listOf(
    FilterOption("전체", ""),
    FilterOption("일반웹툰", "normal"),
    FilterOption("BL/GL", "bl"),
    FilterOption("성인웹툰", "adult"),
)

internal val wtDayList = listOf(
    FilterOption("전체", ""),
    FilterOption("월", "월"),
    FilterOption("화", "화"),
    FilterOption("수", "수"),
    FilterOption("목", "목"),
    FilterOption("금", "금"),
    FilterOption("토", "토"),
    FilterOption("일", "일"),
)

fun buildWtGenreParam(genreFilter: WtGenreFilter?): Pair<String?, String?> {
    if (genreFilter == null) return Pair(null, null)
    val include = mutableListOf<String>()
    val exclude = mutableListOf<String>()
    genreFilter.state.filterIsInstance<GenreTriState>().forEachIndexed { index, triState ->
        val tagId = wtGenreList[index].second.toString()
        when (triState.state) {
            Filter.TriState.STATE_INCLUDE -> include.add(tagId)
            Filter.TriState.STATE_EXCLUDE -> exclude.add(tagId)
        }
    }
    return Pair(
        if (include.isNotEmpty()) include.joinToString(",") else null,
        if (exclude.isNotEmpty()) exclude.joinToString(",") else null,
    )
}
