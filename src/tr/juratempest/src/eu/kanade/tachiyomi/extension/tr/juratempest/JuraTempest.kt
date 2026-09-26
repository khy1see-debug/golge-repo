package eu.kanade.tachiyomi.extension.tr.juratempest

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class JuraTempest : ParsedHttpSource() {

    override val name = "Jura Tempest"
    override val baseUrl = "https://juratempe.st"
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

    // /api/rpc/* is behind a Cloudflare rule that only lets same-origin XHR through.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Sec-Fetch-Site", "same-origin")

    // ---------- oRPC helper ----------

    private fun rpc(path: String, payload: String = "{}"): Request =
        POST(
            "$baseUrl/api/rpc/$path",
            headers,
            payload.toRequestBody("application/json".toMediaType()),
        )

    private fun arg(name: String, value: String): String =
        """{"json":{"$name":${json.encodeToString(value)}}}"""

    private inline fun <reified T> decode(body: String): T? =
        runCatching { json.decodeFromString<JtRpc<T>>(body).json }.getOrNull()

    // ---------- Listing ----------

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaSelector(): String = "a[class~=\"group/card\"][href^=\"/explore/\"]:has(img)"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        url = explorePath(element.attr("href"))
        val img = element.selectFirst("img")
        title = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: element.attr("aria-label").removeSuffix(" sayfasını aç").trim()
        thumbnail_url = img?.absUrl("src")
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = rpc("release/latest")

    override fun latestUpdatesParse(response: Response): MangasPage {
        val releases = decode<List<JtReleaseItem>>(response.body?.string().orEmpty()).orEmpty()
        val mangas = releases
            .mapNotNull { it.chapter.manga.takeIf { m -> m.slug.isNotBlank() } }
            .distinctBy { it.slug }
            .map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SManga = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = null

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val q = query.trim()
        // Arama uç noktası en az 3 karakter istiyor; aksi halde 400 döner.
        if (q.length < MIN_QUERY_LENGTH) return Observable.just(MangasPage(emptyList(), false))
        val offset = (page - 1) * SEARCH_LIMIT
        val payload = """{"json":{"q":${json.encodeToString(q)},"limit":$SEARCH_LIMIT,"offset":$offset}}"""
        return client.newCall(rpc("search/manga", payload))
            .asObservableSuccess()
            .map { response ->
                val result = decode<JtSearchResult>(response.body?.string().orEmpty())
                val mangas = result?.hits.orEmpty().map { it.toSManga() }.distinctBy { it.url }
                val hasNextPage = offset + mangas.size < (result?.estimatedTotalHits ?: 0)
                MangasPage(mangas, hasNextPage)
            }
    }

    override fun searchMangaSelector(): String = throw UnsupportedOperationException()
    override fun searchMangaFromElement(element: Element): SManga = throw UnsupportedOperationException()
    override fun searchMangaNextPageSelector(): String? = null
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()

    // ---------- Details ----------

    override fun mangaDetailsRequest(manga: SManga): Request =
        rpc("manga/bySlug", arg("slug", slugOf(manga.url)))

    override fun mangaDetailsParse(response: Response): SManga {
        val result = SManga.create()
        val data = decode<JtManga>(response.body?.string().orEmpty())
        if (data == null) {
            result.initialized = true
            return result
        }
        result.title = data.titleTr.ifBlank { data.titleEn }
        result.thumbnail_url = data.coverImageUrl.takeIf { it.isNotBlank() }
        result.description = data.description.ifBlank { data.synopsis }
        result.genre = data.genres.joinToString(", ") { it.name }
        result.author = data.writers.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
        result.artist = data.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
        result.status = when (data.seriesStatus) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        result.initialized = true
        return result
    }

    override fun mangaDetailsParse(document: Document): SManga = throw UnsupportedOperationException()

    // ---------- Chapters ----------
    // Sayfa yalnızca son 10 bölümü SSR ile basıyor; tamamı oRPC'den gelir.

    override fun chapterListRequest(manga: SManga): Request {
        val slug = slugOf(manga.url)
        return rpc("chapter/byMangaSlug?slug=$slug", arg("slug", slug))
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()
        val slug = response.request.url.queryParameter("slug").orEmpty()

        val chapters = decode<List<JtChapter>>(body).orEmpty()
        if (chapters.isNotEmpty()) {
            return chapters
                .filter { it.slug.isNotBlank() }
                .map {
                    SChapter.create().apply {
                        url = "$slug/${it.slug}"
                        name = it.title.ifBlank { "Bölüm ${it.number.trimNumber()}" }
                        chapter_number = it.number
                        date_upload = parseDate(it.createdAt)
                    }
                }
                .distinctBy { it.url }
                .sortedByDescending { it.chapter_number }
        }

        // oRPC erişilemezse SSR satırlarına düş.
        return Jsoup.parse(body)
            .select("a[href^=\"/explore/$slug/\"]")
            .map { chapterFromElement(it) }
            .distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    override fun chapterListSelector(): String = "a[href^=\"/explore/\"]"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val href = element.attr("href")
        url = href.trimStart('/').substringAfter("explore/")
        name = element.text().trim().ifBlank { "Bölüm" }
        chapter_number = Regex("([0-9]+(?:\\.[0-9]+)?)").find(name)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }

    // ---------- Pages ----------

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.trim('/').split("/")
        val mangaSlug = parts.getOrNull(0).orEmpty()
        val chapterSlug = parts.getOrNull(1).orEmpty()
        val payload = """{"json":{"mangaSlug":${json.encodeToString(mangaSlug)},""" +
            """"chapterSlug":${json.encodeToString(chapterSlug)}}}"""
        return rpc("release/byChapterSlug", payload)
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string().orEmpty()
        val releases = decode<List<JtRelease>>(body).orEmpty()
        val pages = releases
            .flatMap { it.pages }
            .sortedBy { it.number }
            .mapNotNull { it.imageUrl.takeIf(String::isNotBlank) }

        if (pages.isNotEmpty()) {
            return pages.mapIndexed { index, url -> Page(index, "", url) }
        }

        return Jsoup.parse(body).select("img[src*=\"cdn.juratempe.st\"]")
            .mapIndexed { index, element -> Page(index, "", element.absUrl("src")) }
    }

    override fun pageListParse(document: Document): List<Page> = throw UnsupportedOperationException()

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()
}

private const val MIN_QUERY_LENGTH = 3
private const val SEARCH_LIMIT = 50

private fun explorePath(href: String): String {
    val parts = href.split("/").filter { it.isNotBlank() }.take(2)
    return if (parts.isEmpty()) href else "/" + parts.joinToString("/")
}

private fun slugOf(url: String): String = url.trim('/').substringAfterLast('/')

private fun Float.trimNumber(): String =
    if (this % 1.0f == 0.0f) toInt().toString() else toString()

private fun parseDate(raw: String): Long {
    if (raw.isBlank()) return 0L
    val normalized = raw.replace(Regex("\\.\\d+"), "")
    val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
    )
    for (pattern in formats) {
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(normalized)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return 0L
}

private fun JtManga.toSManga(): SManga = SManga.create().apply {
    url = "/explore/$slug"
    title = titleTr.ifBlank { titleEn }
    thumbnail_url = coverImageUrl.takeIf { it.isNotBlank() }
}

@Serializable
private class JtRpc<T>(val json: T)

@Serializable
private class JtNamed(val name: String = "")

@Serializable
private class JtManga(
    val slug: String = "",
    val titleTr: String = "",
    val titleEn: String = "",
    val description: String = "",
    val synopsis: String = "",
    val seriesStatus: String = "",
    val coverImageUrl: String = "",
    val genres: List<JtNamed> = emptyList(),
    val writers: List<JtNamed> = emptyList(),
    val artists: List<JtNamed> = emptyList(),
)

@Serializable
private class JtSearchResult(
    val hits: List<JtManga> = emptyList(),
    val estimatedTotalHits: Int = 0,
)

@Serializable
private class JtChapter(
    val slug: String = "",
    val number: Float = -1f,
    val title: String = "",
    val createdAt: String = "",
)

@Serializable
private class JtLatestChapter(
    val slug: String = "",
    val number: Float = 0f,
    val manga: JtManga = JtManga(),
)

@Serializable
private class JtReleaseItem(val chapter: JtLatestChapter = JtLatestChapter())

@Serializable
private class JtReleasePage(val number: Int = 0, val imageUrl: String = "")

@Serializable
private class JtRelease(val pages: List<JtReleasePage> = emptyList())
