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
    scalaVersion: Option[String],
    scalacOptions: Option[Seq[String]],
    pomSettings: IrPom,
    publishVersion: String,
    publishProperties: Seq[(String, String)],
    repositories: Seq[String]
)

case class IrPom(
    description: String,
    organization: String,
    url: String,
    licenses: Seq[IrLicense],
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

// TODO Consider renaming to `IrModule(Build)` to disambiguate? sbt, for example, uses `ThisBuild` and `buildSettings` to refer to the whole build.
case class IrBuild(
    scopedDeps: IrScopedDeps,
    testModule: String,
    hasTest: Boolean,
    dirs: Seq[String],
    repositories: Seq[String],
    javacOptions: Seq[String],
    scalaVersion : Option[String],
    scalacOptions: Option[Seq[String]],
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
    // TODO The type is `Seq` and this is deduplicated and sorted in `BuildGenUtil`. Make the type `SortedMap` here for consistency?
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
    testCompileModuleDeps: SortedSet[String] = SortedSet()
)

case class IrBaseInfo(
    javacOptions: Seq[String] = Nil,
    scalaVersion : Option[String] = None,
    scalacOptions : Option[Seq[String]] = None,
    repositories: Seq[String] = Nil,
    noPom: Boolean = true,
    publishVersion: String = "",
    publishProperties: Seq[(String, String)] = Nil,
    moduleTypedef: IrTrait = null
)

sealed class IrDependencyType
object IrDependencyType {
  case object Default extends IrDependencyType
  case object Test extends IrDependencyType
  case object Compile extends IrDependencyType
  case object Run extends IrDependencyType
}
