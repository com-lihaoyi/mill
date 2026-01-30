package mill.testkit

/**
 * Renders parsed chunks from a build.mill file to AsciiDoc format.
 * Used both for generating documentation and for test workspace setup.
 *
 * @param parsed The parsed chunks from ExampleParser
 * @param linkInfo Optional link information for download/browse links.
 *                 If None, renders without links (for tests).
 *                 If Some, includes download and browse links (for docs).
 */
object ExampleRenderer {

  case class LinkInfo(
      downloadUrl: String,
      browseUrlPrefix: String
  )

  def render(parsed: Seq[Chunk], linkInfo: Option[LinkInfo] = None): String = {
    var seenCode = false
    var seenFrontMatter = false

    val frontMatter = parsed.takeWhile(_.isInstanceOf[Chunk.Yaml]).collect {
      case Chunk.Yaml(lines) => lines.mkString("\n")
    }.mkString("\n")

    val withoutFrontMatter = parsed.dropWhile(_.isInstanceOf[Chunk.Yaml])
    val hasScalaChunks = withoutFrontMatter.exists(_.isInstanceOf[Chunk.Scala])

    val sourceAttrs = linkInfo match {
      case Some(_) => """,subs="attributes,verbatim""""
      case None => ""
    }

    def buildMillTitle(first: Boolean): String = linkInfo match {
      case Some(info) if first =>
        s".build.mill (${info.downloadUrl}, ${info.browseUrlPrefix}[browse])"
      case Some(_) => ""
      case None if first => ".build.mill"
      case None => ""
    }

    def seeAlsoTitle(path: String): String = linkInfo match {
      case Some(info) =>
        s".$path (${info.downloadUrl}, ${info.browseUrlPrefix}/$path[browse])"
      case None => s".$path"
    }

    val renderedChunks = withoutFrontMatter
      .map {
        case Chunk.See(path, lines) =>
          val lang = os.FilePath(path).ext match {
            case "mill" => "scala"
            case s => s
          }
          s"""
${seeAlsoTitle(path)}
[source,$lang$sourceAttrs]
----
${lines.mkString("\n")}
----"""
        case Chunk.Scala(lines) =>
          val title = buildMillTitle(!seenCode)
          seenCode = true
          s"""
$title
[source,scala$sourceAttrs]
----

${
              if (seenFrontMatter) ""
              else {
                seenFrontMatter = true
                frontMatter
              }
            }

${lines.mkString("\n")}
----
"""
        case Chunk.Comment(lines) => lines.mkString("\n") + "\n"
        case Chunk.Usage(lines) =>
          s"""
[source,console$sourceAttrs]
----
${lines.mkString("\n")}
----"""
        case Chunk.Yaml(_) => ""
      }
      .mkString("\n")

    // If no Scala chunks exist but we have front matter, include it at the top
    if (!hasScalaChunks && frontMatter.nonEmpty) {
      s"""
${buildMillTitle(true)}
[source,scala$sourceAttrs]
----
$frontMatter
----
""" + renderedChunks
    } else {
      renderedChunks
    }
  }
}
