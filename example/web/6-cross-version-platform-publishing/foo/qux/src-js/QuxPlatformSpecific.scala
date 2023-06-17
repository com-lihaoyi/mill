package qux
import scala.scalajs.js
object QuxPlatformSpecific {
  def parseJsonGetKeys(s: String): Set[String] = {
    println("Parsing JSON with js.JSON.parse")
    js.JSON.parse(s).asInstanceOf[js.Dictionary[_]].keys.toSet
  }
}
