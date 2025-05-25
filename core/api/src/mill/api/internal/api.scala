package mill.api.internal

import mill.api.*

trait TaskApi[+T]
trait NamedTaskApi[+T] extends TaskApi[T] {
  def label: String
}
trait ModuleApi {
  def moduleDirectChildren: Seq[ModuleApi]
  private[mill] def moduleDirJava: java.nio.file.Path
  def moduleSegments: Segments
}
trait JavaModuleApi extends ModuleApi {
  private[mill] def bspBuildTargetScalaMainClasses
      : TaskApi[(Seq[String], Seq[String], Map[String, String])]
  def recursiveModuleDeps: Seq[JavaModuleApi]
  def compileModuleDepsChecked: Seq[JavaModuleApi]
  private[mill] def bspRun(args: Seq[String]): TaskApi[Unit]
  private[mill] def bspBuildTargetSources
      : TaskApi[(Seq[java.nio.file.Path], Seq[java.nio.file.Path])]

  private[mill] def bspBuildTargetInverseSources[T](id: T, uri: String): TaskApi[Seq[T]]

  private[mill] def bspBuildTargetDependencySources
      : TaskApi[(Seq[java.nio.file.Path], Seq[java.nio.file.Path])]

  private[mill] def bspBuildTargetDependencyModules
      : TaskApi[(Seq[(String, String, String)], Seq[java.nio.file.Path])]

  private[mill] def bspBuildTargetResources: TaskApi[Seq[java.nio.file.Path]]

  private[mill] def bspBuildTargetCompile: TaskApi[java.nio.file.Path]

  private[mill] def bspLoggingTest: TaskApi[Unit]

  private[mill] def bspBuildTargetJavacOptions(clientWantsSemanticDb: Boolean)
      : TaskApi[EvaluatorApi => (java.nio.file.Path, Seq[String], Seq[String])]

  private[mill] def bspCompileClasspath: TaskApi[EvaluatorApi => Seq[String]]

  private[mill] def bspBuildTargetScalacOptions(
      enableJvmCompileClasspathProvider: Boolean,
      clientWantsSemanticDb: Boolean
  ): TaskApi[(Seq[String], EvaluatorApi => Seq[String], EvaluatorApi => java.nio.file.Path)]

  private[mill] def genIdeaMetadata(
      ideaConfigVersion: Int,
      evaluator: EvaluatorApi,
      path: mill.api.Segments
  ): TaskApi[ResolvedModule]

  def transitiveModuleCompileModuleDeps: Seq[JavaModuleApi]
  def skipIdea: Boolean
  private[mill] def intellijModulePathJava: java.nio.file.Path
}
object JavaModuleApi

trait ScalaModuleApi extends JavaModuleApi
trait ScalaJSModuleApi extends JavaModuleApi
trait ScalaNativeModuleApi extends JavaModuleApi
trait TestModuleApi extends ModuleApi {
  def testLocal(args: String*): TaskApi[(msg: String, results: Seq[Any])]
  private[mill] def bspBuildTargetScalaTestClasses
      : TaskApi[(frameworkName: String, classes: Seq[String])]
}
trait MainModuleApi extends ModuleApi {
  private[mill] def bspClean(
      evaluator: EvaluatorApi,
      targets: String*
  ): TaskApi[Seq[java.nio.file.Path]]
}
trait BspModuleApi extends ModuleApi {
  private[mill] def bspBuildTargetData: TaskApi[Option[(String, AnyRef)]]
  private[mill] def bspBuildTarget: BspBuildTarget
  private[mill] def bspDisplayName: String
}
trait RunModuleApi extends ModuleApi {
  private[mill] def bspJvmRunTestEnvironment: TaskApi[(
      Seq[java.nio.file.Path],
      Seq[String],
      java.nio.file.Path,
      Map[String, String],
      Option[String],
      Any
  )]
}

object BspModuleApi {

  /** Used to define the [[BspBuildTarget.languageIds]] field. */
  object LanguageId {
    val Java = "java"
    val Scala = "scala"
    val Kotlin = "kotlin"
  }

  /** Used to define the [[BspBuildTarget.tags]] field. */
  object Tag {
    val Library = "library"
    val Application = "application"
    val Test = "test"
    val IntegrationTest = "integration-test"
    val Benchmark = "benchmark"
    val NoIDE = "no-ide"
    val Manual = "manual"
  }
}

final case class ResolvedModule(
    path: Segments,
    classpath: Seq[Scoped[java.nio.file.Path]],
    module: JavaModuleApi,
    pluginClasspath: Seq[java.nio.file.Path],
    scalaOptions: Seq[String],
    scalaCompilerClasspath: Seq[java.nio.file.Path],
    libraryClasspath: Seq[java.nio.file.Path],
    facets: Seq[JavaFacet],
    configFileContributions: Seq[IdeaConfigFile],
    compilerOutput: java.nio.file.Path,
    scalaVersion: Option[String],
    resources: Seq[java.nio.file.Path],
    generatedSources: Seq[java.nio.file.Path],
    allSources: Seq[java.nio.file.Path]
)

final case class Scoped[T](value: T, scope: Option[String])

final case class JavaFacet(`type`: String, name: String, config: Element)

/**
 * Encoding of an Idea XML configuration fragment.
 * @param name The XML element name
 * @param attributes The optional XML element attributes
 * @param childs The optional XML child elements.
 */
final case class Element(
    name: String,
    attributes: Map[String, String] = Map(),
    childs: Seq[Element] = Seq()
)

/**
 * An Idea config file contribution
 *
 * @param subPath   The sub-path of the config file, relative to the Idea config directory (`.idea`)
 * @param component The Idea component
 * @param config    The actual (XML) configuration, encoded as [[Element]]s
 *
 *                  Note: the `name` fields is deprecated in favour of `subPath`, but kept for backward compatibility.
 */
final case class IdeaConfigFile(
    subPath: java.nio.file.Path,
    component: Option[String],
    config: Seq[Element]
) {
  // An empty component name meas we contribute a whole file
  // If we have a fill file, we only accept a single root xml node.
  require(
    component.forall(_.nonEmpty) && (component.nonEmpty || config.size == 1),
    "Files contributions must have exactly one root element."
  )

  def asWholeFile: Option[(java.nio.file.Path, Element)] =
    if (component.isEmpty) {
      Option(subPath -> config.head)
    } else None
}

object IdeaConfigFile {

  /** Alternative creator accepting a component string. */
  def apply(
      subPath: java.nio.file.Path,
      component: String,
      config: Seq[Element]
  ): IdeaConfigFile =
    IdeaConfigFile(subPath, if (component == "") None else Option(component), config)
}

trait PathRefApi {
  private[mill] def javaPath: java.nio.file.Path
  def quick: Boolean
  def sig: Int
}
