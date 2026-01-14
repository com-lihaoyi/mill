//| mvnDeps: [org.jsoup:jsoup:1.7.2]
import org.jsoup.Jsoup

fun fetchLinks(title: String): List<String> {
    val url = "https://en.wikipedia.org/wiki/$title"
    val doc = Jsoup.connect(url).header("User-Agent", "My Scraper").get()

    return doc.select("main p a")
        .mapNotNull { a ->
            val href = a.attr("href")
            if (href.startsWith("/wiki/")) href.removePrefix("/wiki/")
            else null
        }
}

fun main(args: Array<String>) {
    if (args.size < 2) throw Exception("HtmlScraper.kt <start> <depth>")
    var seen = mutableSetOf(args[0])
    var current = mutableSetOf(args[0])

    repeat(args[1].toInt()) {
        val next = mutableSetOf<String>()
        for (article in current) {
            for (link in fetchLinks(article)) {
                if (seen.add(link)) next.add(link)
            }
        }
        current = next
    }
    seen.forEach { println(it) }
}
