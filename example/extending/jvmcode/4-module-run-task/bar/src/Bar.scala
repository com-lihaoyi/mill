package bar
object Bar {
  def main(args: Array[String]) = {
    val dest = os.pwd
    for (sourceStr <- args) {
      val sourcePath = os.Path(sourceStr)
      for (p <- os.walk(sourcePath) if p.ext == "scala") {
        val text = os.read(p)
        val mangledText = text.replace("hello", "HELLO")
        val fileDest = dest / (p.subRelativeTo(sourcePath))
        os.write(fileDest, mangledText)
      }
    }
  }
}
