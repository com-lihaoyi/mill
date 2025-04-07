package mill.runner.api

trait TaskApi[+T]
trait NamedTaskApi[+T] extends TaskApi[T] {
  def label: String
}
trait ModuleApi {
  def moduleDirectChildren: Seq[ModuleApi]
  def moduleDirJava: java.nio.file.Path
  def moduleSegments: Segments
}
trait JavaModuleApi extends ModuleApi {
  def bspBuildTargetScalaMainClasses: TaskApi[(Seq[String], Seq[String], Map[String, String])]
  def recursiveModuleDeps: Seq[JavaModuleApi]
  def compileModuleDepsChecked: Seq[JavaModuleApi]
  def bspRun(args: Seq[String]): TaskApi[Unit]
  def bspBuildTargetSources: TaskApi[(Seq[java.nio.file.Path], Seq[java.nio.file.Path])]
//  Task.Anon {
//    module.sources().map(p => sourceItem(p.path, false)) ++
//      module.generatedSources().map(p => sourceItem(p.path, true))
//  }

  def bspBuildTargetInverseSources[T](id: T, uri: String): TaskApi[Seq[T]]
//  Task.Anon {
//    val src = m.allSourceFiles()
//    val found = src.map(sanitizeUri).contains(
//      p.getTextDocument.getUri
//    )
//    if (found) Seq(id) else Seq()
//  }

  def bspBuildTargetDependencySources(includeSources: Boolean)
      : TaskApi[(Seq[java.nio.file.Path], Seq[java.nio.file.Path], Seq[String])]
//  Task.Anon {
//    (
//      m.millResolver().classpath(
//        Seq(
//          m.coursierDependency.withConfiguration(coursier.core.Configuration.provided),
//          m.coursierDependency
//        ),
//        sources = true
//      ),
//      m.unmanagedClasspath(),
//      m.allRepositories()
//    )
//  }

  def bspBuildTargetDependencyModules
      : TaskApi[(Seq[(String, String, String)], Seq[java.nio.file.Path])]
//  Task.Anon {
//    (
//      // full list of dependencies, including transitive ones
//      m.millResolver()
//        .resolution(
//          Seq(
//            m.coursierDependency.withConfiguration(coursier.core.Configuration.provided),
//            m.coursierDependency
//          )
//        )
//        .orderedDependencies,
//      m.unmanagedClasspath()
//    )
//  }

  def bspBuildTargetResources: TaskApi[Seq[java.nio.file.Path]]
//  Task.Anon {
//    m.resources()
//  }

  def bspBuildTargetCompile: TaskApi[java.nio.file.Path]

  def bspBuildTargetJavacOptions(clientWantsSemanticDb: Boolean)
      : TaskApi[EvaluatorApi => (java.nio.file.Path, Seq[String], Seq[String])]

  def bspCompileClasspath: TaskApi[EvaluatorApi => Seq[String]]

  def bspBuildTargetScalacOptions(
      enableJvmCompileClasspathProvider: Boolean,
      clientWantsSemanticDb: Boolean
  ): TaskApi[(Seq[String], EvaluatorApi => Seq[String], EvaluatorApi => java.nio.file.Path)]

  def genIdeaMetadata(
      ideaConfigVersion: Int,
      evaluator: mill.runner.api.EvaluatorApi,
      path: mill.runner.api.Segments
  ): TaskApi[ResolvedModule]

  def transitiveModuleCompileModuleDeps: Seq[JavaModuleApi]
  def skipIdea: Boolean
  def intellijModulePathJava: java.nio.file.Path
  def buildLibraryPaths: TaskApi[Seq[java.nio.file.Path]]
}
object JavaModuleApi

trait ScalaModuleApi extends JavaModuleApi
trait ScalaJSModuleApi extends JavaModuleApi
trait ScalaNativeModuleApi extends JavaModuleApi
trait TestModuleApi extends ModuleApi {
  def testLocal(args: String*): TaskApi[(String, Seq[Any])]
}
trait BspModuleApi extends ModuleApi {
  def bspBuildTargetData: TaskApi[Option[(String, AnyRef)]]
  def bspBuildTarget: BspBuildTarget
  def bspDisplayName: String
}
trait RunModuleApi extends ModuleApi {
  def bspJvmRunTestEnvironment: TaskApi[(
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
    evaluator: EvaluatorApi,
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
