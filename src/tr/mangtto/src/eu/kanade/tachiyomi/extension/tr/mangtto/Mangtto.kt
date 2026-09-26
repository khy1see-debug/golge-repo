package eu.kanade.tachiyomi.extension.tr.mangtto

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class Mangtto : HttpSource() {

    override val name = "Mangtto"
    override val baseUrl = "https://mangtto.com"
    override val lang = "tr"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/manga/${chapter.url}"

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val skip = (page - 1) * LIST_PAGE_SIZE
        return GET("$baseUrl/api/manga/populer?skip=$skip&take=$LIST_PAGE_SIZE", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val data = json.decodeFromString<MangttoPopularData>(response.body?.string().orEmpty())
        val mangas = data.mangas.map { it.toSManga() }.distinctBy { it.url }
        val hasNextPage = skipOf(response) + data.mangas.size < data.total
        return MangasPage(mangas, hasNextPage && mangas.isNotEmpty())
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val skip = (page - 1) * LIST_PAGE_SIZE
        return GET("$baseUrl/api/manga/latest?skip=$skip&take=$LIST_PAGE_SIZE", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = json.decodeFromString<MangttoLatestData>(response.body?.string().orEmpty())
        val mangas = data.chapters
            .mapNotNull { it.manga }
            .distinctBy { it.slug }
            .map { it.toSManga() }
        val hasNextPage = skipOf(response) + data.chapters.size < data.total
        return MangasPage(mangas, hasNextPage && mangas.isNotEmpty())
    }

    // Search
    // q sunucu tarafında en az 3 karakter istiyor ve sayfa boyutu sabit 42.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/manga/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val data = json.decodeFromString<MangttoSearchData>(response.body?.string().orEmpty())
        val mangas = data.hits.map { it.document.toSManga() }.distinctBy { it.url }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = page * SEARCH_PAGE_SIZE < data.estimatedTotalHits && mangas.isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl/api/manga/${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = json.decodeFromString<MangttoDetailData>(response.body?.string().orEmpty())
        return data.toSManga()
    }

    // Chapters
    // take=50 sunucudaki maksimum. Parametresiz istek yalnızca 20 bölüm döndürür.
    override fun chapterListRequest(manga: SManga): Request {
        val url = "$baseUrl/api/manga/${manga.url}/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("skip", "0")
            .addQueryParameter("take", CHAPTER_PAGE_SIZE.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val baseUrl = response.request.url
        val path = baseUrl.encodedPath.trimEnd('/').split("/")
        val mangaSlug = path.getOrNull(path.size - 2).orEmpty()

        val first = readChapterPage(response)
        val collected = LinkedHashMap<String, MangttoChapter>()
        first.chapters.forEach { collected[it.chapter.toString()] = it }

        val take = first.take.takeIf { it > 0 } ?: CHAPTER_PAGE_SIZE
        var skip = first.chapters.size
        while (skip < first.total) {
            val next = client.newCall(
                Request.Builder()
                    .url(baseUrl.newBuilder().setQueryParameter("skip", skip.toString()).build())
                    .headers(headers)
                    .build()
            ).execute()
            val page = next.use { readChapterPage(it) }
            if (page.chapters.isEmpty()) break
            page.chapters.forEach { collected[it.chapter.toString()] = it }
            skip += page.chapters.size
        }

        return collected.values
            .map { it.toSChapter(mangaSlug) }
            .sortedByDescending { it.chapter_number }
    }

    private fun readChapterPage(response: Response): MangttoChapterPageData =
        json.decodeFromString(response.body?.string().orEmpty())

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl/api/manga/${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = json.decodeFromString<MangttoPageData>(response.body?.string().orEmpty())
        val upload = data.uploads.firstOrNull() ?: return emptyList()
        val fansubId = upload.fansubId ?: return emptyList()

        // Extract slug and chapter number from the request URL path
        // URL: /api/manga/<slug>/<chNum>
        val path = response.request.url.encodedPath
        val parts = path.trimEnd('/').split("/")
        val slug = parts.getOrNull(parts.size - 2) ?: ""
        val chNum = parts.lastOrNull() ?: ""

        if (slug.isEmpty() || chNum.isEmpty() || fansubId.isEmpty()) return emptyList()

        return (1..upload.fileLength).map { i ->
            val imgUrl = "${data.cdn}/manga/$slug/$chNum/$i-$fansubId.webp"
            Page(i - 1, "", imgUrl)
        }
    }

    private fun skipOf(response: Response): Int =
        response.request.url.queryParameter("skip")?.toIntOrNull() ?: 0
}

private const val LIST_PAGE_SIZE = 24
private const val CHAPTER_PAGE_SIZE = 50
private const val SEARCH_PAGE_SIZE = 42
