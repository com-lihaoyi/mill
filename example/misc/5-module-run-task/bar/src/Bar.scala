package bar
object Bar {
  def main(args: Array[String]) = {
    val Array(sourcesStrJoined, destStr) = args
    val sourceStrs = sourcesStrJoined.split(",")
    val dest = os.Path(destStr)
    for(sourceStr <- sourceStrs){
      val sourcePath = os.Path(sourceStr)
      for(p <- os.walk(sourcePath) if p.ext == "scala"){
        val text = os.read(p)
        val mangledText = text.replace("hello", "HELLO")
        val fileDest = dest / (p.subRelativeTo(sourcePath))
        os.write(fileDest, mangledText)
      }
    }
  }
}
