package mill.scalalib.bsp

import mill.api.{experimental, internal}
import mill.define.Task
import mill.scalalib.internal.ModuleUtils
import mill._

trait BspModule extends Module {
  import BspModule._

  def bspDisplayName0: String = ModuleUtils.moduleDisplayName(this)

  def bspDisplayName: String = bspDisplayName0 match {
    case "" => "root-module"
    case n => n
  }

  /** Use to fill most fields of `BuildTarget`. */
  @internal
  def bspBuildTarget: BspBuildTarget = BspBuildTarget(
    displayName = Some(bspDisplayName),
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

  /**
   * Extensions to load when BSP is used.
   */
  @internal
  @experimental
  def bspExtensions: Seq[BspExtension] = Seq()

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
