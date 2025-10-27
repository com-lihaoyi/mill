//| mvnDeps:
//| - com.lihaoyi::scalatags:0.13.1
//| - com.atlassian.commonmark:commonmark:0.13.1
import scalatags.Text.all._

def main(args: Array[String]): Unit = {
  val postInfo = os
    .list(os.pwd / "post")
    .map{ p =>
      val s"$prefix - $suffix.md" = p.last
      (prefix, suffix, p)
    }
    .sortBy(_._1.toInt)

  os.remove.all(os.pwd / "site-out")
  os.makeDir.all(os.pwd / "site-out/post")

  def writeHtml(subPath: os.SubPath, pageTitle: Frag, pageBody: Frag) = {
    os.write(
      os.pwd / subPath,
      doctype("html")(
        html(body(pageTitle, pageBody))
      )
    )
  }

  for ((_, suffix, path) <- postInfo) {
    val parser = org.commonmark.parser.Parser.builder().build()
    val document = parser.parse(os.read(path))
    val renderer = org.commonmark.renderer.html.HtmlRenderer.builder().build()
    val output = renderer.render(document)
    writeHtml(
      os.sub / "site-out/post" / (suffix.replace(" ", "-").toLowerCase + ".html"),
      h1(a("Blog"), " / ", suffix),
      raw(output)
    )
  }
  writeHtml(
    "site-out/index.html",
    h1("Blog"),
    for ((_, suffix, _) <- postInfo) yield h2(suffix)
  )
}
