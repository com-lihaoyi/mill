package mill.api.internal

object Util {

  def leftPad(s: String, targetLength: Int, char: Char): String = {
    char.toString * (targetLength - s.length) + s
  }

  def renderSecondsSuffix(millis: Long): String =
    (millis / 1000).toInt match {
      case 0 => ""
      case n => s" ${n}s"
    }

  /**
   * Scrape colored line content from Dotty/Zinc rendered output.
   * Looks for lines matching the pattern `  <linenum> |<code>` and extracts the code part.
   *
   * @param renderedLines The lines from the rendered error output
   * @param fallback A fallback value if no matching line is found
   * @return The scraped line content or the fallback
   */
  def scrapeColoredLineContent(renderedLines: Seq[String], unMappedPosLine: Int, fallback: => String): String = {
    renderedLines
      .collectFirst {
        case s"$pre |$rest" if pre.nonEmpty && fansi.Str(pre).plainText.trim == unMappedPosLine.toString =>
          rest
      }
      .getOrElse(fallback)
  }

}
