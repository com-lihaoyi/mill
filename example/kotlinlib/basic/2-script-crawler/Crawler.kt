//| mvnDeps:
//| - org.jetbrains.kotlinx:kotlinx-cli:0.3.6
//| - com.squareup.okhttp3:okhttp:4.12.0
//| - org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3

import kotlinx.cli.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.nio.file.*

fun fetchLinks(title: String): List<String> {
    val client = OkHttpClient()
    val url = HttpUrl.Builder()
        .scheme("https")
        .host("en.wikipedia.org")
        .addPathSegments("w/api.php")
        .addQueryParameter("action", "query")
        .addQueryParameter("titles", title)
        .addQueryParameter("prop", "links")
        .addQueryParameter("format", "json")
        .build()

    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "WikiFetcherBot/1.0 (https://example.com; contact@example.com)")
        .build()

    client.newCall(request).execute().use { resp ->
        val body = resp.body?.string() ?: return emptyList()
        val json = Json.parseToJsonElement(body).jsonObject
        val pages = json["query"]?.jsonObject?.get("pages")?.jsonObject ?: return emptyList()
        return pages.values.flatMap { page ->
            page.jsonObject["links"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content }
                ?: emptyList()
        }
    }
}

fun main(args: Array<String>) {
    val parser = ArgParser("wiki-fetcher")
    val startArticle by parser.option(ArgType.String, description = "Starting Wikipedia article").required()
    val depth by parser.option(ArgType.Int, description = "Depth of link traversal").required()
    parser.parse(args)

    var seen = mutableSetOf(startArticle)
    var current = mutableSetOf(startArticle)

    repeat(depth) {
        val next = current.flatMap { fetchLinks(it) }.toSet()
        current = (next - seen).toMutableSet()
        seen += current
    }

    val jsonOut = Json { prettyPrint = true }
        .encodeToString(JsonElement.serializer(), JsonArray(seen.map { JsonPrimitive(it) }))
    Files.writeString(Paths.get("fetched.json"), jsonOut)
}