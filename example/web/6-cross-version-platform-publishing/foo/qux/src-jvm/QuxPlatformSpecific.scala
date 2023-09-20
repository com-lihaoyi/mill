package qux
object QuxPlatformSpecific {
  def parseJsonGetKeys(s: String): Set[String] = {
    println("Parsing JSON with ujson.read")
    ujson.read(s).obj.keys.toSet
  }
}
