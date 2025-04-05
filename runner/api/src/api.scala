package mill.runner.api

trait TaskApi[+T]
trait NamedTaskApi[+T] extends TaskApi[T]
trait JavaModuleApi {
  def recursiveModuleDeps: Seq[JavaModuleApi]
  def compileModuleDepsChecked: Seq[JavaModuleApi]

  def buildTargetSources: TaskApi[Seq[java.nio.file.Path]]
//  Task.Anon {
//    module.sources().map(p => sourceItem(p.path, false)) ++
//      module.generatedSources().map(p => sourceItem(p.path, true))
//  }

  def buildTargetInverseSources[T](id: T, uri: String): TaskApi[Seq[T]]
//  Task.Anon {
//    val src = m.allSourceFiles()
//    val found = src.map(sanitizeUri).contains(
//      p.getTextDocument.getUri
//    )
//    if (found) Seq(id) else Seq()
//  }

  def buildTargetDependencySources: TaskApi[(Seq[java.nio.file.Path], Seq[java.nio.file.Path], Any)]
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

  def buildTargetDependencyModules: TaskApi[(Any, Seq[java.nio.file.Path])]
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

  def buildTargetResources: TaskApi[Seq[java.nio.file.Path]]
//  Task.Anon {
//    m.resources()
//  }

}
object JavaModuleApi

trait TestModuleApi
trait BspModuleApi {
  def bspBuildTargetData: TaskApi[Option[(String, AnyRef)]]
  def bspBuildTarget: BspBuildTarget
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
