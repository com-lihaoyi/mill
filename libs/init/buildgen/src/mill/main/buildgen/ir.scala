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
    pomSettings: IrPom | Null,
    publishVersion: String | Null,
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

// TODO reuse the members in `IrTrait`?
case class IrModuleBuild(
    scopedDeps: IrScopedDeps,
    testModule: String,
    testModuleMainType: String,
    hasTest: Boolean,
    dirs: Seq[String],
    repositories: Seq[String],
    javacOptions: Seq[String],
    scalaVersion: Option[String],
    scalacOptions: Option[Seq[String]],
    projectName: String,
    pomSettings: IrPom | Null,
    publishVersion: String | Null,
    packaging: String | Null,
    pomParentArtifact: IrArtifact | Null,
    resources: Seq[os.SubPath],
    testResources: Seq[os.SubPath],
    publishProperties: Seq[(String, String)],
    jvmId: Option[String],
    testForkDir: Option[String]
)

object IrModuleBuild {
  // TODO not used
  def empty(dirs: Seq[String]) = IrModuleBuild(
    IrScopedDeps(),
    null,
    null,
    false,
    dirs,
    Seq.empty,
    Seq.empty,
    None,
    None,
    dirs.last,
    null,
    null,
    null,
    null,
    Seq.empty,
    Seq.empty,
    Seq.empty,
    None,
    None
  )
}

case class IrScopedDeps(
    // TODO The type is `Seq` and this is deduplicated and sorted in `BuildGenUtil`. Make the type `SortedMap` here for consistency?
    namedMvnDeps: Seq[(String, String)] = Nil,
    mainBomMvnDeps: SortedSet[String] = SortedSet(),
    mainMvnDeps: SortedSet[String] = SortedSet(),
    mainModuleDeps: SortedSet[String] = SortedSet(),
    mainCompileMvnDeps: SortedSet[String] = SortedSet(),
    mainCompileModuleDeps: SortedSet[String] = SortedSet(),
    mainRunMvnDeps: SortedSet[String] = SortedSet(),
    mainRunModuleDeps: SortedSet[String] = SortedSet(),
    testModule: Option[String] = None,
    testBomMvnDeps: SortedSet[String] = SortedSet(),
    testMvnDeps: SortedSet[String] = SortedSet(),
    testModuleDeps: SortedSet[String] = SortedSet(),
    testCompileMvnDeps: SortedSet[String] = SortedSet(),
    testCompileModuleDeps: SortedSet[String] = SortedSet()
)

// TODO remove `IrBaseInfo` and just use `IrTrait` directly?
case class IrBaseInfo(
    /*
    javacOptions: Seq[String] = Nil,
    scalaVersion: Option[String] = None,
    scalacOptions: Option[Seq[String]] = None,
    repositories: Seq[String] = Nil,
    noPom: Boolean = true,
    publishVersion: String = "",
    publishProperties: Seq[(String, String)] = Nil,
     */
    // TODO consider renaming directly to `trait` or `baseTrait`?
    moduleTypedef: IrTrait | Null = null
)

sealed class IrDependencyType
object IrDependencyType {
  case object Default extends IrDependencyType
  case object Test extends IrDependencyType
  case object Compile extends IrDependencyType
  case object Run extends IrDependencyType
}
