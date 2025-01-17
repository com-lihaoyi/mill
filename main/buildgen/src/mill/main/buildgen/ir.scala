package mill.main.buildgen

import scala.collection.immutable.{SortedMap, SortedSet}

/**
 * A Mill build module defined as a Scala object.
 *
 * @param imports Scala import statements
 * @param companions build companion objects defining constants
 * @param supertypes Scala supertypes inherited by the object
 * @param inner Scala object code
 * @param outer additional Scala type definitions like base module traits
 */
@mill.api.experimental
case class BuildObject(
    imports: SortedSet[String],
    companions: BuildObject.Companions,
    supertypes: Seq[String],
    inner: String,
    outer: String
)
@mill.api.experimental
object BuildObject {

  type Constants = SortedMap[String, String]
  type Companions = SortedMap[String, Constants]
}

/**
 * A node representing a module in a build tree.
 *
 * @param dirs relative location in the build tree
 * @param module build module
 */
@mill.api.experimental
case class Node[Module](dirs: Seq[String], module: Module)
