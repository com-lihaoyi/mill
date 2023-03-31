package foo
import scala.scalajs.js
object FooPlatformSpecific {
  def parseJsonGetKeys(s: String): Set[String] = {
    println("Parsing JSON with js.JSON.parse")
    js.JSON.parse(s).asInstanceOf[js.Dictionary[_]].keys.toSet
  }
}
