//| mvnDeps: [org.jsoup:jsoup:1.7.2]
import org.jsoup._
import scala.collection.JavaConverters._
import java.io.File

def fetchLinks(title: String): Seq[String] = {
  val file = new File(s"./wikipedia-$title.html")
  Jsoup.parse(file, "UTF-8")
    .select("main p a").asScala.toSeq.map(_.attr("href"))
    .collect { case s"/wiki/$rest" => rest }
}

@main
def main(startArticle: String, depth: Int) = {
  var seen = Set(startArticle)
  var current = Set(startArticle)
  for (i <- Range(0, depth)) {
    current = current.flatMap(fetchLinks(_)).filter(!seen.contains(_))
    seen = seen ++ current
  }

  pprint.log(seen, height = Int.MaxValue)
}
