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
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful())
        throw new IOException("Unexpected code " + response);

      JsonNode root = mapper.readTree(response.body().byteStream());
      JsonNode pages = root.path("query").path("pages");
      List<String> links = new ArrayList<>();

      for (Iterator<JsonNode> it = pages.elements(); it.hasNext();) {
        JsonNode linkArr = it.next().get("links");
        if (linkArr != null && linkArr.isArray()) {
          for (JsonNode link : linkArr) {
            JsonNode titleNode = link.get("title");
            if (titleNode != null) links.add(titleNode.asText());
          }
        }
      }
      return links;
    }
  }

  @Override
  public Integer call() throws Exception {
    Set<String> seen = new HashSet<>();
    Set<String> current = new HashSet<>();
    seen.add(startArticle);
    current.add(startArticle);

    for (int i = 0; i < depth; i++) {
      Set<String> next = new HashSet<>();
      for (String article : current) {
        for (String link : fetchLinks(article)) {
          if (!seen.contains(link)) next.add(link);
        }
      }
      seen.addAll(next);
      current = next;
    }

    Path output = Paths.get("fetched.json");
    try (Writer w = Files.newBufferedWriter(output)) {
      DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
      printer.indentArraysWith(new DefaultIndenter("    ", "\n"));
      printer.indentObjectsWith(new DefaultIndenter("    ", "\n"));
      mapper.writer(printer).writeValue(w, seen);
    }
    return 0;
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new Crawler()).execute(args);
    System.exit(exitCode);
  }
}
