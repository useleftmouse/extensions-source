package eu.kanade.tachiyomi.extension.ko.ntk

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

abstract class NTKBase(
    override val name: String,
    protected val contentKind: String,
) : HttpSource(),
    ConfigurableSource {

    private val json = Json { ignoreUnknownKeys = true }

    protected val apiHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "application/json")
            .build()
    }

    override val lang = "ko"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Returns the root URL from preferences, normalized.
    // Accepts a plain number (e.g. "3") → "https://sbxh3.com",
    // a bare hostname (e.g. "sbxh4.com") → "https://sbxh4.com",
    // or a full URL (e.g. "https://sbxh5.com") → "https://sbxh5.com".
    protected val rootUrl: String
        get() {
            val stored = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
            val normalized = normalizeRootUrl(stored)
            if (normalized != stored) {
                preferences.edit().putString(PREF_DOMAIN_KEY, normalized).apply()
            }
            return normalized
        }

    private fun normalizeRootUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) return PREF_DOMAIN_DEFAULT

        if (trimmed.all { it.isDigit() }) {
            val domainNumber = trimmed.trimStart('0').ifEmpty { "0" }
            return "https://sbxh$domainNumber.com"
        }

        val url = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }.toHttpUrlOrNull()

        return url?.let { "${it.scheme}://${it.host}" } ?: PREF_DOMAIN_DEFAULT
    }

    protected open val webViewPath: String get() = contentKind
    override val baseUrl: String get() = "$rootUrl/$webViewPath"

    override fun mangaDetailsRequest(manga: SManga) = GET(rootUrl + manga.url, headers)
    override fun chapterListRequest(manga: SManga) = GET(rootUrl + manga.url, headers)
    override fun pageListRequest(chapter: SChapter) = GET(rootUrl + chapter.url, headers)

    // Strips Next.js RSC headers that would cause the server to return partial JSON.
    private val headerCleanerInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
            .removeHeader("rsc")
            .removeHeader("next-router-state-tree")
            .removeHeader("next-url")

        if (original.header("Accept") == null) {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        }

        chain.proceed(builder.build())
    }

    // Detects when the site redirects to a new sbxh{n}.com domain and saves it automatically.
    private val domainUpdateInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        val currentRootUrl = normalizeRootUrl(preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!)
        val currentHost = currentRootUrl.toHttpUrlOrNull()?.host
        val finalUrl = response.request.url
        val finalRootUrl = "${finalUrl.scheme}://${finalUrl.host}"

        if (request.url.host == currentHost && finalRootUrl != currentRootUrl) {
            preferences.edit().putString(PREF_DOMAIN_KEY, finalRootUrl).apply()
        }

        response
    }

    private var lastImageRequestTime = 0L
    private val smartRateLimitInterceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()

        val isImage = url.endsWith(".jpg") || url.endsWith(".png") || url.endsWith(".webp")
        val isDownload = request.header("X-Download") != null

        if (isImage && isDownload) {
            val rateLimitSeconds = preferences.getString(PREF_RATELIMIT_KEY, PREF_RATELIMIT_DEFAULT)!!.toLong()
            if (rateLimitSeconds > 0) {
                val delayMillis = rateLimitSeconds * 1000L
                synchronized(this) {
                    val now = System.currentTimeMillis()
                    val timeToWait = delayMillis - (now - lastImageRequestTime)
                    if (timeToWait > 0) Thread.sleep(timeToWait)
                    lastImageRequestTime = System.currentTimeMillis()
                }
            }
        }
        chain.proceed(request)
    }

    private val webViewRedirectInterceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        if (url == rootUrl || url == "$rootUrl/") {
            chain.proceed(request.newBuilder().url("$rootUrl/$webViewPath").build())
        } else {
            chain.proceed(request)
        }
    }

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .addInterceptor(headerCleanerInterceptor)
            .addInterceptor(domainUpdateInterceptor)
            .addInterceptor(smartRateLimitInterceptor)
            .addInterceptor(webViewRedirectInterceptor)
            .build()
    }

    // Parses HTML card grid (used by text search)
    protected fun htmlCardParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.card-grid > a.card").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.select("p.subject").text()
                thumbnail_url = element.select("div.thumb img:not(.platform-icon)").attr("abs:src")
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    // Parses JSON API response (popular / filter search)
    override fun popularMangaParse(response: Response): MangasPage {
        val data = json.parseToJsonElement(response.body.string()).jsonObject
        val mangas = data["works"]!!.jsonArray.map {
            val work = it.jsonObject
            SManga.create().apply {
                url = "/$contentKind/${work["sourceWorkId"]!!.jsonPrimitive.content}"
                title = work["title"]!!.jsonPrimitive.content
                thumbnail_url = work["thumbnailUrl"]?.jsonPrimitive?.content
                genre = work["genre"]?.jsonPrimitive?.content
            }
        }
        return MangasPage(mangas, data["hasMore"]!!.jsonPrimitive.boolean)
    }

    // Parses the latest updates page — all entries are pre-loaded in a Next.js RSC script.
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val rscData = document.select("script")
            .map { it.data() }
            .firstOrNull { "allCards" in it }
            ?: return MangasPage(emptyList(), false)

        val rawContent = rscData
            .substringAfter("[1,\"")
            .substringBeforeLast("\"])")

        val unescaped = rawContent
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\/", "/")

        val marker = "\"allCards\":"
        val markerIdx = unescaped.indexOf(marker)
        if (markerIdx < 0) return MangasPage(emptyList(), false)

        val arrayStart = markerIdx + marker.length
        var depth = 0
        var arrayEnd = arrayStart
        for (i in arrayStart until unescaped.length) {
            when (unescaped[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = i + 1
                        break
                    }
                }
            }
        }

        val cards = json.parseToJsonElement(unescaped.substring(arrayStart, arrayEnd)).jsonArray

        val seen = mutableSetOf<String>()
        val mangas = cards.mapNotNull {
            val card = it.jsonObject
            val sid = card["sourceWorkId"]!!.jsonPrimitive.content
            if (seen.add(sid)) {
                SManga.create().apply {
                    url = "/$contentKind/$sid"
                    title = card["workTitle"]!!.jsonPrimitive.content
                    thumbnail_url = card["thumbnailUrl"]?.jsonPrimitive?.content
                    genre = card["genre"]?.jsonPrimitive?.content
                    author = card["author"]?.jsonPrimitive?.content
                }
            } else {
                null
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val contentType = response.header("Content-Type") ?: ""
        return if (contentType.contains("application/json")) {
            popularMangaParse(response)
        } else {
            htmlCardParse(response)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.select("h1.hero-v2-title").text()
            author = document.select("div.hero-v2-author a").text()
            description = document.select("p.hero-v2-desc").text()
            thumbnail_url = document.select("div.hero-v2-thumb img").attr("abs:src")
            status = when {
                document.select("span.pill-status").text().contains("연재중") -> SManga.ONGOING
                document.select("span.pill-status").text().contains("완결") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = document.select("a.hero-v2-tag").joinToString(", ") {
                it.text().replace("#", "").trim()
            }
        }
    }

    private val dateFormat = SimpleDateFormat("yy.MM.dd", Locale.KOREA)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("ul.ep-list-v2 > li.ep-row-v2").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.select("a.ep-row-v2-link").attr("href"))
                name = element.select("div.ep-row-v2-title strong").text()
                date_upload = element.select("span.ep-row-v2-date").text()
                    .let { runCatching { dateFormat.parse(it)?.time ?: 0L }.getOrDefault(0L) }
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div.vw-imgs img").mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "도메인 URL"
            summary = "현재 도메인: $rootUrl\n숫자만 입력하면 해당 번호의 도메인으로 설정됩니다 (예: 2 → sbxh2.com, 3 → sbxh3.com)"
            setDefaultValue(PREF_DOMAIN_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_RATELIMIT_KEY
            title = "다운로드 속도 제한"
            summary = "현재 설정: ${preferences.getString(PREF_RATELIMIT_KEY, PREF_RATELIMIT_DEFAULT)}초마다 1장\n※ 다운로드할 때만 적용됩니다"
            entries = arrayOf(
                "제한 없음 (최고속)",
                "1초마다 다운로드",
                "2초마다 다운로드",
                "3초마다 다운로드",
                "4초마다 다운로드",
                "5초마다 다운로드",
            )
            entryValues = arrayOf("0", "1", "2", "3", "4", "5")
            setDefaultValue(PREF_RATELIMIT_DEFAULT)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        const val PREF_DOMAIN_DEFAULT = "https://sbxh2.com"
        private const val PREF_RATELIMIT_KEY = "pref_ratelimit_key"
        private const val PREF_RATELIMIT_DEFAULT = "3"

        const val PAGE_SIZE = 49
    }
}
