//| mvnDeps:
//| - info.picocli:picocli:4.7.6
//| - com.squareup.okhttp3:okhttp:4.12.0
//| - com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import okhttp3.*;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "Crawler", mixinStandardHelpOptions = true)
public class Crawler implements Callable<Integer> {

  @CommandLine.Option(
    names = {"--start-article"},
    required = true,
    description = "Starting article title"
  )
  private String startArticle;

  @CommandLine.Option(
    names = {"--depth"},
    required = true,
    description = "Depth of crawl"
  )
  private int depth;

  private static final OkHttpClient client = new OkHttpClient();
  private static final ObjectMapper mapper = new ObjectMapper();

  public static List<String> fetchLinks(String title) throws IOException {
    var url = new HttpUrl.Builder()
      .scheme("https")
      .host("en.wikipedia.org")
      .addPathSegments("w/api.php")
      .addQueryParameter("action", "query")
      .addQueryParameter("titles", title)
      .addQueryParameter("prop", "links")
      .addQueryParameter("format", "json")
      .build();

    var request = new Request.Builder()
      .url(url)
      .header("User-Agent", "WikiFetcherBot/1.0 (https://example.com; contact@example.com)")
      .build();

    try (var response = client.newCall(request).execute()) {
      if (!response.isSuccessful())
        throw new IOException("Unexpected code " + response);

      var root = mapper.readTree(response.body().byteStream());
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
  }

  @Override
  public Integer call() throws Exception {
    var seen = new HashSet<String>();
    var current = new HashSet<String>();
    seen.add(startArticle);
    current.add(startArticle);

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

    var output = Paths.get("fetched.json");
    try (var w = Files.newBufferedWriter(output)) {
      var printer = new DefaultPrettyPrinter();
      printer.indentArraysWith(new DefaultIndenter("    ", "\n"));
      printer.indentObjectsWith(new DefaultIndenter("    ", "\n"));
      mapper.writer(printer).writeValue(w, seen);
    }
    return 0;
  }

  public static void main(String[] args) {
    System.exit(new CommandLine(new Crawler()).execute(args));
  }
}
