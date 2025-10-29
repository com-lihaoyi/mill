//| mvnDeps: [org.jsoup:jsoup:1.7.2]

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

fun fetchLinks(title: String): List<String> {
    val url = "https://en.wikipedia.org/wiki/$title"
    val doc: Document = Jsoup.connect(url)
        .header("User-Agent", "Mozilla/5.0 (compatible; JsoupBot/1.0; +https://example.com/bot)")
        .get()

    return doc.select("main p a")
        .mapNotNull { a ->
            val href = a.attr("href")
            if (href.startsWith("/wiki/")) href.removePrefix("/wiki/") else null
        }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: kotlin Scraper <startArticle> <depth>")
        return
    }

    val startArticle = args[0]
    val depth = args[1].toInt()

    var seen = mutableSetOf(startArticle)
    var current = mutableSetOf(startArticle)

    repeat(depth) {
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