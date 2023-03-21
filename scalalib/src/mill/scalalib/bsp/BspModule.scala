package mill.scalalib.bsp

import mill.util.SpecialClassLoader
import mill.api.{Loose, PathRef, internal}
import mill.define.{BaseModule, Segments, Sources, Task}
import mill.eval.EvaluatorPathsResolver
import mill.modules.Jvm
import mill.scalalib.api.CompilationResult
import mill.scalalib.internal.ModuleUtils
import mill.scalalib.{Dep, DepSyntax, ScalaModule}
import mill.{Agg, BuildInfo, Module, T}

trait BspModule extends Module {
  import BspModule._

  /** Use to fill most fields of `BuildTarget`. */
  @internal
  def bspBuildTarget: BspBuildTarget = BspBuildTarget(
    displayName = Some(ModuleUtils.moduleDisplayName(this)),
    baseDirectory = Some(millSourcePath),
    tags = Seq(Tag.Library, Tag.Application),
    languageIds = Seq(),
    canCompile = false,
    canTest = false,
    canRun = false,
    canDebug = false
  )

  /**
   * Use to populate the `BuildTarget.{dataKind,data}` fields.
   *
   * Mill specific implementations:
   * - [[JvmBuildTarget]]
   * - [[ScalaBuildTarget]]
   */
  @internal
  def bspBuildTargetData: Task[Option[(String, AnyRef)]] = T.task { None }

}

object BspModule {
  /** Used to define the [[BspBuildTarget.languageIds]] field. */
  object LanguageId {
    val Java = "java"
    val Scala = "scala"
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
