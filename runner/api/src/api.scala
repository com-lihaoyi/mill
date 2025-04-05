package mill.runner.api

trait TaskApi[+T]
trait NamedTaskApi[+T] extends TaskApi[T]
trait JavaModuleApi {
  def recursiveModuleDeps: Seq[JavaModuleApi]
  def compileModuleDepsChecked: Seq[JavaModuleApi]
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
