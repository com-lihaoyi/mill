package bar
object Bar {
  def main(args: Array[String]) = {
    println("LOLS")
    val Array(sourcesStrJoined, destStr) = args
    val sourceStrs = sourcesStrJoined.split(",")
    println("sourceStrs " + sourceStrs.toList)
    println("destStr " + destStr)
    val dest = os.Path(destStr)
    for(sourceStr <- sourceStrs){
      val sourcePath = os.Path(sourceStr)
      println("sourcePath " + sourcePath)
      for(p <- os.walk(sourcePath) if p.ext == "scala"){
        println("p " + p)
        val text = os.read(p)
        println("text " + text)
        val mangledText = text.replace("hello", "HELLO")
        println("mangledText " + mangledText)
        val fileDest = dest / (p.subRelativeTo(sourcePath))
        println("fileDest " + fileDest)
        os.write(fileDest, mangledText)
      }
    }
  }
}
