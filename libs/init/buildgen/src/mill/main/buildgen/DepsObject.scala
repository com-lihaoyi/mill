package mill.main.buildgen

import mill.internal.Util.backtickWrap

import scala.collection.mutable

case class DepsObject(name: String, depNames: mutable.Map[String, String] = mutable.Map()) {
  private val usedNames = mutable.Set.empty[String]
  val artifactRegex = """:([^:"]+)[:"]""".r

  def renderName(dep: String) = {
    var depName = depNames.getOrElse(dep, null)
    if (depName == null) {
      val artifact = artifactRegex.findFirstMatchIn(dep).get.group(1)
      depName = artifact.split("\\W") match
        case Array(head) => head
        case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
      val count = usedNames.count(_.startsWith(depName))
      // we want the "duplicates" to stand out visually
      // using '#' forces backticks in the final name
      depName = depName + (if (count == 0) "" else s"#$count")
      usedNames += depName
      depName = backtickWrap(depName)
      depNames.put(dep, depName)
    }
    name + "." + depName
  }
}
