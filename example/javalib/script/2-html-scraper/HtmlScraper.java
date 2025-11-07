//| mvnDeps: [org.jsoup:jsoup:1.7.2]
import org.jsoup.Jsoup;
import java.util.*;

public class HtmlScraper {
  static List<String> fetchLinks(String title) throws Exception {
    var url = "https://en.wikipedia.org/wiki/" + title;
    var doc = Jsoup.connect(url).header("User-Agent", "My Scraper").get();

    var links = new ArrayList<String>();
    for (var a : doc.select("main p a")) {
      var href = a.attr("href");
      if (href.startsWith("/wiki/")) links.add(href.substring(6));
    }
    return links;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) throw new Exception("WikiCrawler.java <start> <depth>");

    var seen = new HashSet<>(Set.of(args[0]));
    var current = new HashSet<>(Set.of(args[0]));
    for (int i = 0; i < Integer.parseInt(args[1]); i++) {
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

