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
    .replaceAll("\\(([a-zA-Z.]+):\\d+\\)", "($1:<digits>)")
    .replaceAll("\\d+]", "<digits>]")
    .replaceAll("\\d+]", "<digits>]")
    .replaceAll("\\d+/\\d+", ".../...")
    .replaceAll("\\d+\\.\\d+", ".../...")
    .replaceAll(" \\d+s", "")
    .replaceAll("===+", "<dashes>")
    .linesIterator
    .toList

}
