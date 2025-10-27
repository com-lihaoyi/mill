//| mvnDeps:
//| - com.j2html:j2html:1.6.0
//| - com.atlassian.commonmark:commonmark:0.13.1

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import j2html.tags.ContainerTag;
import j2html.tags.specialized.HtmlTag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static j2html.TagCreator.*;

public class StaticSite {
  public record PostInfo(int index, String title, String slug, Path path) {
    public static PostInfo fromPath(Path path) {
      var fileName = path.getFileName().toString();

      var dashIdx = fileName.indexOf(" - ");
      var dotIdx = fileName.lastIndexOf(".md");
      var prefixStr = fileName.substring(0, dashIdx).trim();
      var titleStr = fileName.substring(dashIdx + 3, dotIdx).trim();

      var idxVal = Integer.parseInt(prefixStr);
      var slugVal = titleStr.toLowerCase().replace(" ", "-");

      return new PostInfo(idxVal, titleStr, slugVal, path);
    }
  }

  public static void main(String[] args) throws IOException {
    var cwd = Paths.get("").toAbsolutePath();
    var postDir = cwd.resolve("post");
    var outDir = cwd.resolve("site-out");
    var outPostDir = outDir.resolve("post");

    // 1. Collect & sort posts
    // We'll expand the stream pipeline so we can use vars.
    var posts = new ArrayList<PostInfo>();
    try (var dirStream = Files.list(postDir)) {
      for (var pathIter = dirStream.iterator(); pathIter.hasNext(); ) {
        var p = pathIter.next();
        if (Files.isRegularFile(p)) posts.add(PostInfo.fromPath(p));
      }
    }
    posts.sort(Comparator.comparingInt(PostInfo::index));

    deleteRecursively(outDir);
    Files.createDirectories(outPostDir);

    var mdParser = Parser.builder().build();
    var mdRenderer = HtmlRenderer.builder().build();

    for (var post : posts) {
      var markdown = Files.readString(post.path(), StandardCharsets.UTF_8);
      var renderedHtml = mdRenderer.render(mdParser.parse(markdown));
      writeFile(
        outPostDir.resolve(post.slug() + ".html"),
        "<!DOCTYPE html>\n" + renderPostPage(post.title(), renderedHtml)
      );
    }

    var postHeadings = div();
    for (var p : posts) {
      postHeadings.with(h2(a(p.title()).withHref("post/" + p.slug() + ".html")));
    }

    writeFile(
      outDir.resolve("index.html"),
      "<!DOCTYPE html>\n" +
        html(
          head(meta().withCharset("UTF-8"), title("Blog")),
          body(h1("Blog"), postHeadings)
        ).attr("lang", "en").renderFormatted()
    );
  }

  private static String renderPostPage(String title, String contentHtml) {
    var doc =
      html(
        head(meta().withCharset("UTF-8"), title("Blog / " + title)),
        body(
          h1("Blog /" + title),
          div().with(rawHtml(contentHtml))
        )
      ).attr("lang", "en");

    return doc.renderFormatted();
  }

  private static void writeFile(Path dest, String content) throws IOException {
    Files.createDirectories(dest.getParent());
    Files.writeString(
      dest,
      content,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    );
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (var walk = Files.walk(root)) {
      var paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
      for (var p : paths) Files.delete(p);
    }
  }
}
