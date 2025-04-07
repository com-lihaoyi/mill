package mill.runner.api

trait TaskApi[+T]
trait NamedTaskApi[+T] extends TaskApi[T]{
  def label: String
}
trait ModuleApi {
  def moduleDirectChildren: Seq[ModuleApi]
  def moduleDirJava: java.nio.file.Path
  def moduleSegments: Segments
}
trait JavaModuleApi extends ModuleApi{
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

  def bspBuildTargetDependencySources(includeSources: Boolean): TaskApi[(Seq[java.nio.file.Path], Seq[java.nio.file.Path], Seq[String])]
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

  def bspBuildTargetDependencyModules: TaskApi[(Seq[(String, String, String)], Seq[java.nio.file.Path])]
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

  def bspBuildTargetJavacOptions(clientWantsSemanticDb: Boolean): TaskApi[EvaluatorApi => (java.nio.file.Path, Seq[String], Seq[String])]

  def bspCompileClasspath: TaskApi[EvaluatorApi => Seq[String]]

  def bspBuildTargetScalacOptions(
                                   enableJvmCompileClasspathProvider: Boolean,
                                   clientWantsSemanticDb: Boolean
                                 ): TaskApi[(Seq[String], EvaluatorApi => Seq[String], EvaluatorApi => java.nio.file.Path)]
}
object JavaModuleApi

trait TestModuleApi extends ModuleApi {
  def testLocal(args: String*): TaskApi[(String, Seq[Any])]
}
trait BspModuleApi extends ModuleApi{
  def bspBuildTargetData: TaskApi[Option[(String, AnyRef)]]
  def bspBuildTarget: BspBuildTarget
  def bspDisplayName: String
}
trait RunModuleApi extends ModuleApi{
  def bspJvmRunTestEnvironment: TaskApi[(Seq[java.nio.file.Path], Seq[String], java.nio.file.Path, Map[String, String], Option[String], Any)]
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
