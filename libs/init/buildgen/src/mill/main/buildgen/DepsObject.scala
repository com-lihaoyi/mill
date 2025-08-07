package mill.main.buildgen

import mill.internal.Util.backtickWrap

import scala.collection.mutable

case class DepsObject(name: String, depNames: mutable.Map[String, String] = mutable.Map()) {
  private val names = mutable.Set.empty[String]

  def renderName(dep: String) = {
    var depName = depNames.getOrElse(dep, null)
    if (depName == null) {
      depName = dep.dropWhile(_ != ':').dropWhile(_ == ':').takeWhile(_ != ':').split("\\W") match
        case Array(head) => head
        case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
      val count = names.count(_.startsWith(depName))
      depName = depName + (if (count == 0) "" else s"#$count")
      names += depName
      depName = backtickWrap(depName)
      depNames.put(dep, depName)
    }
    name + "." + depName
  }
}
