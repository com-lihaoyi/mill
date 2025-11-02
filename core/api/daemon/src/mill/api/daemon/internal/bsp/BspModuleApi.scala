package mill.api.daemon.internal.bsp

import mill.api.daemon.internal.{ModuleApi, TaskApi}

trait BspModuleApi extends ModuleApi {
  private[mill] def bspBuildTargetData: TaskApi[Option[(String, AnyRef)]]
  private[mill] def bspBuildTarget: BspBuildTarget
  private[mill] def bspDisplayName: String

  /**
   * Set this to false to make the Mill BSP server hide / ignore that module
   *
   * Beware that if a module depends via `moduleDeps` or `compileModuleDeps` on modules
   * that have `enableBsp` set to false, it will be ignored by the Mill BSP server too
   */
  def enableBsp: Boolean = true
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
