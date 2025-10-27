//| mvnDeps: [org.jsoup:jsoup:1.7.2]
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.util.*;

public class Scraper {
  static List<String> fetchLinks(String title) throws IOException {
    String url = "https://en.wikipedia.org/wiki/" + title;
    Document doc = Jsoup.connect(url)
      .header("User-Agent", "Mozilla/5.0 (compatible; JsoupBot/1.0; +https://example.com/bot)")
      .get();

    List<String> links = new ArrayList<>();
    for (Element a : doc.select("main p a")) {
      var href = a.attr("href");
      if (href.startsWith("/wiki/")) links.add(href.substring(6));
    }
    return links;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: java WikiCrawler <startArticle> <depth>");
      System.exit(1);
    }

    var startArticle = args[0];
    var depth = Integer.parseInt(args[1]);

    var seen = new HashSet<String>(Set.of(startArticle));
    var current = new HashSet<String>(Set.of(startArticle));

    for (int i = 0; i < depth; i++) {
      var next = new HashSet<String>();
      for (String article : current) {
        for (String link : fetchLinks(article)) {
          if (seen.add(link)) next.add(link);
        }
      }
      current = next;
    }

    for (String s : seen) System.out.println(s);
  }
}
