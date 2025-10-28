//| mvnDeps: [org.jsoup:jsoup:1.7.2]
import org.jsoup._
import scala.collection.JavaConverters._

def fetchLinks(title: String): Seq[String] = {
  Jsoup.connect(s"https://en.wikipedia.org/wiki/$title")
    .header("User-Agent", "Mozilla/5.0 (compatible; JsoupBot/1.0; +https://example.com/bot)")
    .get().select("main p a").asScala.toSeq.map(_.attr("href"))
    .collect { case s"/wiki/$rest" => rest }
}

@mainargs.main
def main(startArticle: String, depth: Int) = {
  var seen = Set(startArticle)
  var current = Set(startArticle)
  for (i <- Range(0, depth)) {
    current = current.flatMap(fetchLinks(_)).filter(!seen.contains(_))
    seen = seen ++ current
  }

  pprint.log(seen)
}

def main(args: Array[String]): Unit = mainargs.Parser(this).runOrExit(args)
