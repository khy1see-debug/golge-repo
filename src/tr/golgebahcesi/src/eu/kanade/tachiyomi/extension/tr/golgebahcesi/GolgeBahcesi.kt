package eu.kanade.tachiyomi.extension.tr.golgebahcesi

import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class GolgeBahcesi : HttpSource() {

    override val name = "Gölge Bahçesi"
    override val baseUrl = "https://golgebahcesi.com"
    private val apiBaseUrl = "https://api.golgebahcesi.com/api"
    override val lang = "tr"
    override val supportsLatest = true

    private val cookieSyncInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        try {
            val cookieManager = CookieManager.getInstance()
            val mainCookies = cookieManager.getCookie(baseUrl)
            if (!mainCookies.isNullOrBlank()) {
                val existingCookie = originalRequest.header("Cookie")
                val mergedCookies = if (existingCookie.isNullOrBlank()) {
                    mainCookies
                } else {
                    "$existingCookie; $mainCookies"
                }
                requestBuilder.header("Cookie", mergedCookies)
            }
        } catch (_: Exception) {
            // Ignore if CookieManager not available
        }

        val response = chain.proceed(requestBuilder.build())

        if (response.code == 403) {
            val bodyString = response.peekBody(1024).string()
            if (bodyString.contains("challenge") || bodyString.contains("turnstile") || bodyString.contains("Just a moment")) {
                throw Exception("Cloudflare doğrulaması gerekiyor. Lütfen seriyi WebView (küre simgesi) ile açıp doğrulamayı tamamlayın.")
            }
        }

        response
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(cookieSyncInterceptor)
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiBaseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("sort", "popular")
            .build()
        return GET(url.toString(), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseSeriesList(response)

    // Latest Manga
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBaseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("sort", "updatedAt")
            .build()
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseSeriesList(response)

    // Search Manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiBaseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("search", query)
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSeriesList(response)

    private fun parseSeriesList(response: Response): MangasPage {
        val json = JSONObject(response.body?.string().orEmpty())
        val data = json.optJSONArray("data") ?: JSONArray()
        val pagination = json.optJSONObject("pagination")

        val hasNextPage = if (pagination != null) {
            val currentPage = pagination.optInt("currentPage", 1)
            val totalPages = pagination.optInt("totalPages", 1)
            currentPage < totalPages
        } else {
            false
        }

        val mangas = mutableListOf<SManga>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val manga = SManga.create().apply {
                url = obj.optString("slug")
                title = obj.optString("title")
                thumbnail_url = obj.optString("coverImage").takeIf { it.isNotBlank() }
            }
            mangas.add(manga)
        }

        return MangasPage(mangas, hasNextPage)
    }

    // Manga Details
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiBaseUrl/series/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = JSONObject(response.body?.string().orEmpty())
        return SManga.create().apply {
            url = obj.optString("slug")
            title = obj.optString("title")
            thumbnail_url = obj.optString("coverImage").takeIf { it.isNotBlank() }
            description = obj.optString("description").takeIf { it.isNotBlank() }
            author = obj.optString("author").takeIf { it.isNotBlank() }
            artist = obj.optString("artist").takeIf { it.isNotBlank() } ?: author

            val genresList = mutableListOf<String>()
            val genresArr = obj.optJSONArray("genres")
            if (genresArr != null) {
                for (i in 0 until genresArr.length()) {
                    genresList.add(genresArr.getString(i))
                }
            }
            val type = obj.optString("type")
            if (type.isNotBlank()) {
                genresList.add(type.lowercase().replaceFirstChar { it.uppercase() })
            }
            genre = genresList.distinct().joinToString(", ")

            status = when (obj.optString("status")) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "HIATUS" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    // Chapter List
    override fun chapterListRequest(manga: SManga): Request =
        GET("$apiBaseUrl/series/${manga.url}/chapters", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val arr = JSONArray(response.body?.string().orEmpty())
        val chapters = mutableListOf<SChapter>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        for (i in 0 until arr.length()) {
            val ch = arr.getJSONObject(i)

            val isLocked = ch.optBoolean("isLocked", false)
            if (isLocked) continue

            val seriesSlug = ch.optString("seriesSlug")
            val chapterSlug = ch.optString("slug")
            val chapterId = ch.optString("id")
            if (chapterId.isBlank()) continue

            val chapter = SChapter.create().apply {
                url = "/$seriesSlug/$chapterSlug/$chapterId"
                name = ch.optString("title").ifBlank { "Bölüm ${ch.optDouble("number", 0.0)}" }
                chapter_number = ch.optDouble("number", 0.0).toFloat()

                val dateStr = ch.optString("releaseDate").ifBlank { ch.optString("createdAt") }
                date_upload = if (dateStr.isNotBlank()) {
                    try {
                        val cleanDate = if (dateStr.length >= 19) dateStr.substring(0, 19) else dateStr
                        dateFormat.parse(cleanDate)?.time ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                } else {
                    0L
                }
            }
            chapters.add(chapter)
        }

        return chapters
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.trim('/').split('/')
        return if (parts.size >= 2) {
            val series = parts[0]
            val slug = parts[1]
            "$baseUrl/manga/$series/bolum/$slug"
        } else {
            baseUrl
        }
    }

    // Page List - use /api/chapters/<id> directly
    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.trim('/').split('/')
        val chapterId = parts.lastOrNull() ?: ""
        return GET("$apiBaseUrl/chapters/$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val json = JSONObject(response.body?.string().orEmpty())
        val pagesArr = json.optJSONArray("pages") ?: return emptyList()

        val skycdnBase = "https://c2.skycdn.online"
        val pages = mutableListOf<Page>()

        for (i in 0 until pagesArr.length()) {
            val pageObj = pagesArr.getJSONObject(i)
            val rawUrl = pageObj.optString("url")
            if (rawUrl.isBlank()) continue

            val fullUrl = when {
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                rawUrl.startsWith("//") -> "https:$rawUrl"
                rawUrl.startsWith("/") -> "$skycdnBase$rawUrl"
                else -> "$skycdnBase/$rawUrl"
            }

            pages.add(Page(pages.size, "", fullUrl))
        }

        if (pages.isEmpty() && pagesArr.length() > 0) {
            throw Exception("Bu bölümde sayfa bulunamadı.")
        }

        return pages
    }

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl?.takeIf { it.isNotBlank() } ?: page.url
        return GET(url, headers)
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used.")
    }
}
