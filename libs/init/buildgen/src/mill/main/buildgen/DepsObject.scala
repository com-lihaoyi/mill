package mill.main.buildgen

import mill.internal.Util.backtickWrap

import scala.collection.mutable

case class DepsObject(name: String, refsByDep: mutable.Map[String, String] = mutable.Map.empty) {
  private val artifactRegex = """:([^:"]+)[:"]""".r

  def renderRef(dep: String) = {
    /*
      We use the artifact name as the seed for the reference. When a name collision occurs, a suffix
      is added that starts with the '#' character. This forces backticks in the final name,
      making the "duplicate" stand out visually in the output. The reference without backticks is
      saved in the map so that it is grouped together with the "original", on sort, in the output.
      Example output:
        val catsCore = mvn"org.typelevel::cats-core:2.0.0"
        val `catsCore#0` = mvn"org.typelevel::cats-core:2.6.1"
        val disciplineCore = mvn"org.typelevel::discipline-core::1.7.0"
        val `disciplineCore#0` = mvn"org.typelevel::discipline-core:1.7.0"
        val disciplineMunit = mvn"org.typelevel::discipline-munit:2.0.0"
        val `disciplineMunit#0` = mvn"org.typelevel::discipline-munit::2.0.0"
     */
    var ref = refsByDep.getOrElse(dep, null)
    if (ref == null) {
      val artifact = artifactRegex.findFirstMatchIn(dep).get.group(1)
      ref = artifact.split("\\W") match
        case Array(head) => head
        case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
      if (refsByDep.valuesIterator.contains(ref))
        ref += "#"
        ref += refsByDep.valuesIterator.count(_.startsWith(ref))
      refsByDep.put(dep, ref)
    }
    name + "." + backtickWrap(ref)
  }
}
