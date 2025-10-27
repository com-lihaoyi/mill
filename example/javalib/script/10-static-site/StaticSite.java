//| mvnDeps:
//| - com.j2html:j2html:1.6.0
//| - com.atlassian.commonmark:commonmark:0.13.1

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

import static j2html.TagCreator.*;

public class StaticSite {
  public record PostInfo(int index, String title, String slug, Path path) {
    public static PostInfo fromPath(Path path) {
      var fileName = path.getFileName().toString();
      var matcher = Pattern.compile("(\\d+) - (.+)\\.md").matcher(fileName);
      matcher.matches();
      var titleStr = matcher.group(2).trim();
      var slugVal = titleStr.toLowerCase().replace(" ", "-");
      return new PostInfo(Integer.parseInt(matcher.group(1)), titleStr, slugVal, path);
    }
  }

  public static void main(String[] args) throws Exception {
    var cwd = Paths.get("").toAbsolutePath();
    var outPostDir = cwd.resolve("site-out/post");

    var posts = new ArrayList<PostInfo>();
    try (var dirStream = Files.list(cwd.resolve("post"))) {
      for (var pathIter = dirStream.iterator(); pathIter.hasNext(); ) {
        posts.add(PostInfo.fromPath(pathIter.next()));
      }
    }
    posts.sort(Comparator.comparingInt(PostInfo::index));

    deleteRecursively(cwd.resolve("site-out"));
    Files.createDirectories(outPostDir);

    var mdParser = Parser.builder().build();
    var mdRenderer = HtmlRenderer.builder().build();

    for (var post : posts) {
      var markdown = Files.readString(post.path());
      var renderedHtml = mdRenderer.render(mdParser.parse(markdown));
      Files.writeString(
        outPostDir.resolve(post.slug() + ".html"),
        "<!DOCTYPE html>\n" +
          html(
            body(
              h1("Blog / " + post.title()),
              div().with(rawHtml(renderedHtml))
            )
          ).renderFormatted()
      );
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (var walk = Files.walk(root)) {
      var paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
      for (var p : paths) Files.delete(p);
    }
  }
}
