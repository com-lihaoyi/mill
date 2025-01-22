package mill.main.buildgen

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable

/**
 * A Mill build module defined as a Scala object.
 *
 * @param imports    Scala import statements
 * @param companions build companion objects defining constants
 * @param supertypes Scala supertypes inherited by the object
 * @param inner      Scala object code
 * @param outer      additional Scala type definitions like base module traits
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
 * @param dirs  relative location in the build tree
 * @param value build module
 */
@mill.api.experimental
case class Node[T](dirs: Seq[String], value: T)

case class IrTrait(
    jvmId: Option[String],
    baseModule: String,
    moduleSupertypes: Seq[String],
    javacOptions: Seq[String],
    pomSettings: IrPom,
    publishVersion: String,
    publishProperties: Seq[(String, String)]
)

case class IrPom(
    description: String,
    organization: String,
    url: String,
    licenses: IterableOnce[String],
    versionControl: String,
    developers: IterableOnce[String]
)

case class IrBuild(
    scopedDeps: IrScopedDeps,
    testModule: String,
    hasTest: Boolean,
    dirs: Seq[String],
    repos: Seq[String],
    javacOptions: Seq[String],
    projectName: String,
    pomSettings: IrPom,
    publishVersion: String,
    packaging: String,
    pomParentArtifact: String
)

trait IrScopedDeps {
  val namedIvyDeps = mutable.Buffer.empty[(String, String)]
  val mainBomIvyDeps = mutable.SortedSet.empty[String]
  val mainIvyDeps = mutable.SortedSet.empty[String]
  val mainModuleDeps = mutable.SortedSet.empty[String]
  val mainCompileIvyDeps = mutable.SortedSet.empty[String]
  val mainCompileModuleDeps = mutable.SortedSet.empty[String]
  val mainRunIvyDeps = mutable.SortedSet.empty[String]
  val mainRunModuleDeps = mutable.SortedSet.empty[String]
  var testModule = Option.empty[String]
  val testBomIvyDeps = mutable.SortedSet.empty[String]
  val testIvyDeps = mutable.SortedSet.empty[String]
  val testModuleDeps = mutable.SortedSet.empty[String]
  val testCompileIvyDeps = mutable.SortedSet.empty[String]
  val testCompileModuleDeps = mutable.SortedSet.empty[String]
}
