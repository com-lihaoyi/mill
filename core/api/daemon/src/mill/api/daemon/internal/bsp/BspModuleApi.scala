package mill.api.daemon.internal.bsp

import mill.api.daemon.internal.{ModuleApi, TaskApi}

trait BspModuleApi extends ModuleApi {
  private[mill] def bspBuildTargetData: TaskApi[Option[(String, AnyRef)]]
  private[mill] def bspBuildTarget: BspBuildTarget
  private[mill] def bspDisplayName: String
}

object BspModuleApi {

  /** Used to define the [[BspBuildTarget.languageIds]] field. */
  object LanguageId {
    val Java = "java"
    val Scala = "scala"
    val Kotlin = "kotlin"
    val Groovy = "groovy"
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
