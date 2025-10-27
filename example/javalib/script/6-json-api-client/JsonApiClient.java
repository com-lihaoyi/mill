//| mvnDeps:
//| - info.picocli:picocli:4.7.6
//| - com.konghq:unirest-java:3.14.5
//| - com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.databind.*;
import kong.unirest.Unirest;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "Crawler", mixinStandardHelpOptions = true)
public class JsonApiClient implements Callable<Integer> {

  @CommandLine.Option(names = {"--start-article"}, required = true, description = "Starting title")
  private String startArticle;

  @CommandLine.Option(names = {"--depth"}, required = true, description = "Depth of crawl")
  private int depth;

  private static final ObjectMapper mapper = new ObjectMapper();

  public static List<String> fetchLinks(String title) throws Exception {
    var response = Unirest.get("https://en.wikipedia.org/w/api.php")
      .queryString("action", "query")
      .queryString("titles", title)
      .queryString("prop", "links")
      .queryString("format", "json")
      .header("User-Agent", "WikiFetcherBot/1.0 (https://example.com; contact@example.com)")
      .asString();

    if (!response.isSuccess())
      throw new IOException("Unexpected code " + response.getStatus());

    var root = mapper.readTree(response.getBody());
    var pages = root.path("query").path("pages");
    var links = new ArrayList<String>();

    for (var it = pages.elements(); it.hasNext();) {
      var linkArr = it.next().get("links");
      if (linkArr != null && linkArr.isArray()) {
        for (var link : linkArr) {
          var titleNode = link.get("title");
          if (titleNode != null) links.add(titleNode.asText());
        }
      }
    }
    return links;
  }

  public Integer call() throws Exception {
    var seen = new HashSet<>(Set.of(startArticle));
    var current = new HashSet<>(Set.of(startArticle));

    for (int i = 0; i < depth; i++) {
      var next = new HashSet<String>();
      for (var article : current) {
        for (var link : fetchLinks(article)) {
          if (!seen.contains(link)) next.add(link);
        }
      }
      seen.addAll(next);
      current = next;
    }

    try (var w = Files.newBufferedWriter(Paths.get("fetched.json"))) {
      mapper.writerWithDefaultPrettyPrinter().writeValue(w, seen);
    }
    return 0;
  }

  public static void main(String[] args) {
    System.exit(new CommandLine(new JsonApiClient()).execute(args));
  }
}
