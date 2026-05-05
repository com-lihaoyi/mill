package mill.integration

// Run simple commands on a simple build and check their entire output and some
// metadata files, ensuring we don't get spurious warnings or logging messages
// slipping in and the important parts of the logs and output files are present
object FullRunLogsUtils {

  def normalize(s: String) = s
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
    .toList
    // JDK 23+ prints a "Picked up JDK_JAVA_OPTIONS" NOTE on every Java startup
    // when that env var is set (which we use to silence sun.misc.Unsafe
    // deprecation warnings from Scala's LazyVals). Strip that NOTE here.
    .filterNot(_.contains("Picked up JDK_JAVA_OPTIONS:"))

}
