package mill.main.buildgen

import scala.collection.immutable.{SortedMap, SortedSet}

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
    licenses: IterableOnce[IrLicense],
    versionControl: IrVersionControl,
    developers: Seq[IrDeveloper]
)

case class IrVersionControl(url: String, connection: String, devConnection: String, tag: String)
case class IrDeveloper(
    id: String,
    name: String,
    url: String,
    organization: String,
    organizationUrl: String
)

case class IrArtifact(group: String, id: String, version: String)
case class IrLicense(
    id: String,
    name: String,
    url: String,
    isOsiApproved: Boolean = false,
    isFsfLibre: Boolean = false,
    distribution: String = "repo"
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
    pomParentArtifact: IrArtifact,
    resources: Seq[os.SubPath],
    testResources: Seq[os.SubPath],
    publishProperties: Seq[(String, String)]
)

case class IrScopedDeps(
    namedIvyDeps: Seq[(String, String)] = Nil,
    mainBomIvyDeps: SortedSet[String] = SortedSet(),
    mainIvyDeps: SortedSet[String] = SortedSet(),
    mainModuleDeps: SortedSet[String] = SortedSet(),
    mainCompileIvyDeps: SortedSet[String] = SortedSet(),
    mainCompileModuleDeps: SortedSet[String] = SortedSet(),
    mainRunIvyDeps: SortedSet[String] = SortedSet(),
    mainRunModuleDeps: SortedSet[String] = SortedSet(),
    testModule: Option[String] = None,
    testBomIvyDeps: SortedSet[String] = SortedSet(),
    testIvyDeps: SortedSet[String] = SortedSet(),
    testModuleDeps: SortedSet[String] = SortedSet(),
    testCompileIvyDeps: SortedSet[String] = SortedSet(),
    testCompileModuleDeps: SortedSet[String] = SortedSet(),
    companions: SortedMap[String, BuildObject.Constants] = SortedMap()
)
