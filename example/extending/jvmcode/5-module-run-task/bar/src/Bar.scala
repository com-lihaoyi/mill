package bar
object Bar {
  def main(args: Array[String]) = {
    val dest = os.pwd
    for (sourceStr <- args) {
      // Use `os.Path(_, base)` rather than `os.Path(_)`: Mill may hand us a
      // path string in workspace-relative form (e.g. on reproducible builds),
      // which the single-arg constructor rejects as not absolute.
      val sourcePath = os.Path(sourceStr, os.pwd)
      for (p <- os.walk(sourcePath) if p.ext == "scala") {
        val text = os.read(p)
        val mangledText = text.replace("hello", "HELLO")
        val fileDest = dest / (p.subRelativeTo(sourcePath))
        os.write(fileDest, mangledText)
      }
    }
  }
}
