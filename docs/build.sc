// Load dependencies
import $ivy.{`org.pegdown:pegdown:1.6.0`, `com.lihaoyi::scalatags:0.6.5`}
import $file.pageStyles, pageStyles._
import $file.pages, pages._
import scalatags.Text.all._


import collection.JavaConverters._
import org.pegdown.{PegDownProcessor, ToHtmlSerializer, LinkRenderer, Extensions}
import org.pegdown.ast.{VerbatimNode, ExpImageNode, HeaderNode, TextNode, SimpleNode, TableNode}

val postsFolder = os.pwd/'pages

interp.watch(postsFolder)

val targetFolder = os.pwd/'target


val (markdownFiles, otherFiles) = os.list(postsFolder).partition(_.ext == "md")
markdownFiles.foreach(println)
// Walk the posts/ folder and parse out the name, full- and first-paragraph-
// HTML of each post to be used on their respective pages and on the index

val posts = {
  val split = for(path <- markdownFiles) yield {
    val Array(number, name) = path.last.split(" - ", 2)
    (number, name.stripSuffix(".md"), path)
  }
  for ((index, name, path) <- split.sortBy(_._1.toInt)) yield {
    val processor = new PegDownProcessor(
      Extensions.FENCED_CODE_BLOCKS | Extensions.TABLES | Extensions.AUTOLINKS
    )
    val ast = processor.parseMarkdown(os.read(path).toArray)
    val headers = collection.mutable.Buffer.empty[(String, Int)]
    class Serializer extends ToHtmlSerializer(new LinkRenderer){
      override def printImageTag(rendering: LinkRenderer.Rendering) {
        printer.print("<div style=\"text-align: center\"><img")
        printAttribute("src", rendering.href)
        // shouldn't include the alt attribute if its empty
        if(!rendering.text.equals("")){
          printAttribute("alt", rendering.text)
        }
        import collection.JavaConversions._
        for (attr <- rendering.attributes) {
          printAttribute(attr.name, attr.value)
        }
        printer.print(" style=\"max-width: 100%; max-height: 500px\"")
        printer.print(" /></div>")
      }
      override def visit(node: HeaderNode) = {
        val tag = "h" + node.getLevel()


        val id =
          node
            .getChildren
            .asScala
            .collect{case t: TextNode => t.getText}
            .mkString

        headers.append(id -> node.getLevel())


        val setId = s"id=${'"'+sanitize(id)+'"'}"
        printer.print(s"""<$tag $setId class="${Styles.hoverBox.name}">""")
        visitChildren(node)
        printer.print(
          a(href := ("#" + sanitize(id)), Styles.hoverLink)(
            i(cls := "fa fa-link", aria.hidden := true)
          ).render
        )
        printer.print(s"</$tag>")
      }

      override def visit(node: VerbatimNode) = {
        printer.println().print(
          """<pre style="background-color: #f8f8f8">""" +
            s"""<code style="white-space:pre; background-color: #f8f8f8" class="${node.getType()}">"""
        )

        var text = node.getText()
        // print HTML breaks for all initial newlines
        while(text.charAt(0) == '\n') {
          printer.print("\n")
          text = text.substring(1)
        }
        printer.printEncoded(text)
        printer.print("</code></pre>")
      }
      override def visit(node: TableNode) = {
        currentTableNode = node
        printer.print("<table class=\"table table-bordered\">")
        visitChildren(node)
        printer.print("</table>")
        currentTableNode = null
      }
    }

    val postlude = Seq[Frag](
      hr,
      p(
        i("This documentation was build from mill master branch. ")
      ),
      p(
        b("About Mill: "),
        i(
          "Mill is an Open Source Project created by ",
          a(href:="http://www.lihaoyi.com")("Li Haoyi"), ". ",
          "It is actively maintained and the git repository has ",
          a(href:="https://github.com/lihaoyi/mill/graphs/contributors")("more that 100 individual contributors"),
          " around the world. "
        )
      ),
      p(
        b("About Mills Creator:"),
        i(
          " Li Haoyi is a software engineer, an early contributor to ",
          a(href:="http://www.scala-js.org/")("Scala.js"),
          ", and the author of many open-source Scala tools such as Mill, the ",
          a(href:="http://lihaoyi.com/Ammonite", "Ammonite REPL"), " and ",
          a(href:="https://github.com/lihaoyi/fastparse", "FastParse"), ". ",

          "If you've enjoyed using Mill, or enjoyed using Haoyi's other open ",
          "source libraries, please chip in (or get your Company to chip in!) via ",
          a(href:="https://www.patreon.com/lihaoyi", "Patreon"), " so he can ",
          "continue his open-source work."
        )
      ),
      hr
    ).render

    val rawHtmlContent = new Serializer().toHtml(ast) + postlude
    PostInfo(name, headers, rawHtmlContent)
  }
}

def formatRssDate(date: java.time.LocalDate) = {
  date
    .atTime(0, 0)
    .atZone(java.time.ZoneId.of("UTC"))
    .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
}

@main
def main(publish: Boolean = false, targetDir: os.Path = targetFolder) = {

  os.remove.all(targetDir)

  os.makeDir.all(targetDir/'page)
  for(otherFile <- otherFiles){
    os.copy(otherFile, targetDir/'page/(otherFile relativeTo postsFolder))
  }

  os.copy(os.pwd/"favicon.png", targetDir/"favicon.ico")
  os.copy(os.pwd/"logo-white.svg", targetDir/"logo-white.svg")
  os.copy(os.pwd/"VisualizeCompile.svg", targetDir/"VisualizeCompile.svg")
  os.copy(os.pwd/"VisualizeFoo.svg", targetDir/"VisualizeFoo.svg")
  os.copy(os.pwd/"VisualizePlan.svg", targetDir/"VisualizePlan.svg")

  for(i <- posts.indices){
    val post = posts(i)

    val adjacentLinks = div(display.flex, flexDirection.row, justifyContent.spaceBetween)(
      for((j, isNext) <- Seq(i-1 -> false, i+1 -> true))
        yield posts.lift(j) match{
          case None => div()
          case Some(dest) =>
            renderAdjacentLink(
              isNext,
              dest.name,
              (i == 0, j == 0) match {
                case (true, true) => s"index.html"
                case (true, false) => s"page/${sanitize(dest.name)}.html"
                case (false, true) => s"../index.html"
                case (false, false) => s"${sanitize(dest.name)}.html"
              }
            )
        }
    )


    os.write(
      if (i == 0) targetDir / "index.html"
      else targetDir/'page/s"${sanitize(post.name)}.html",
      postContent(
        i == 0,
        post,
        adjacentLinks,
        posts.map(_.name)
      )
    )
  }

  if (publish){
    os.proc("git", 'init).call(cwd = os.pwd / 'target)
    os.proc("git", 'add, "-A", ".").call(cwd = os.pwd / 'target)
    os.proc("git", 'commit, "-am", "first commit").call(cwd = os.pwd / 'target)
    os.proc("git", 'remote, 'add, 'origin, "git@github.com:lihaoyi/mill.git").call(cwd = os.pwd / 'target)
    os.proc("git", 'push, "-uf", 'origin, "master:gh-pages").call(cwd = os.pwd / 'target)
  }
}
