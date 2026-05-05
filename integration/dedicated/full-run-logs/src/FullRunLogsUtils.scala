package mill.integration

// Run simple commands on a simple build and check their entire output and some
// metadata files, ensuring we don't get spurious warnings or logging messages
// slipping in and the important parts of the logs and output files are present
object FullRunLogsUtils {

  // JDK 23+ prints a `Picked up JDK_JAVA_OPTIONS:` NOTE whenever that env var
  // is set (the test framework sets it to silence `sun.misc.Unsafe`
  // deprecation warnings emitted by Scala's `LazyVals`). When Mill's logger
  // splits the task-name prompt and the NOTE into separate lines, removing
  // the NOTE leaves an orphan prompt line; merge it back onto the next
  // bare-prefix content line so golden output matches.
  private val taskPrefixRe = """(\([BRYGCM]\)<digits>] [a-zA-Z0-9._-]+\(X\))\s*""".r
  private val barePrefixRe = """\([BRYGCM]\)<digits>]\(X\)\s*(.*)""".r

  private def stripJdkJavaOptionsNote(lines: List[String]): List[String] = {
    val out = List.newBuilder[String]
    val it = lines.iterator.filterNot(_.contains("Picked up JDK_JAVA_OPTIONS:")).buffered
    while (it.hasNext) it.next() match {
      case taskPrefixRe(prefix) if it.headOption.exists(barePrefixRe.matches) =>
        val barePrefixRe(content) = it.next(): @unchecked
        out += s"$prefix $content"
      case line => out += line
    }
    out.result()
  }

  def normalize(s: String): List[String] = stripJdkJavaOptionsNote(s
    .replace(fansi.Attr.Reset.escape, "(Z)")
    .replace(fansi.Color.Reset.escape, "(X)")
    .replace(fansi.Color.Red.escape, "(R)")
    .replace(fansi.Color.Green.escape, "(G)")
    .replace(fansi.Color.Blue.escape, "(B)")
    .replace(fansi.Color.Cyan.escape, "(C)")
    .replace(fansi.Color.Magenta.escape, "(M)")
    .replace(fansi.Color.Yellow.escape, "(Y)")
    .replace('\\', '/')
    .replaceAll(
      "\\((\\([A-Z]\\))?([a-zA-Z0-9.$_]+\\.(?:scala|java))(\\([A-Z]\\))?:(\\([A-Z]\\))?\\d+(\\([A-Z]\\))?\\)",
      "($1$2$3:$4<digits>$5)"
    )
    .replaceAll("\\d+]", "<digits>]")
    .replaceAll("\\d+]", "<digits>]")
    .replaceAll("\\d+/\\d+", ".../...")
    .replaceAll("\\d+\\.\\d+", ".../...")
    .replaceAll(" \\d+s", "")
    .replaceAll("===+", "<dashes>")
    .linesIterator
    .toList)

}
