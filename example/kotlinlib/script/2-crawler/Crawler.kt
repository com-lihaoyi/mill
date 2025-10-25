//| mvnDeps:
//| - com.github.ajalt.clikt:clikt:5.0.3
//| - com.konghq:unirest-java:3.14.5
//| - org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.*
import kong.unirest.Unirest
import java.nio.file.*

fun fetchLinks(title: String): List<String> {
    val response = Unirest.get("https://en.wikipedia.org/w/api.php")
        .queryString("action", "query")
        .queryString("titles", title)
        .queryString("prop", "links")
        .queryString("format", "json")
        .header("User-Agent", "WikiFetcherBot/1.0 (https://example.com; contact@example.com)")
        .asString()

    if (!response.isSuccess) return emptyList()

    val json = Json.parseToJsonElement(response.body).jsonObject
    val pages = json["query"]?.jsonObject?.get("pages")?.jsonObject ?: return emptyList()
    return pages.values.flatMap { page ->
        page.jsonObject["links"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content }
            ?: emptyList()
    }
}

class Crawler : CliktCommand(name = "wiki-fetcher") {
    val startArticle by option(help = "Starting Wikipedia article").required()
    val depth by option(help = "Depth of link traversal").int().required()

    override fun run() {
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
}

fun main(args: Array<String>) = Crawler().main(args)