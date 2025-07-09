package mill.javalib.bsp

import mill.api.Task
import mill.*
import mill.api.daemon.internal.bsp.{BspBuildTarget, BspModuleApi}
import mill.api.daemon.internal.bsp.BspModuleApi.Tag
import mill.api.daemon.internal.internal

trait BspModule extends mill.api.Module with BspModuleApi {

  private[mill] def bspDisplayName0: String = this.moduleSegments.render

  private[mill] def bspDisplayName: String = bspDisplayName0 match {
    case "" => "root-module"
    case n => n
  }

  /** Use to fill most fields of `BuildTarget`. */
  @internal
  private[mill] def bspBuildTarget: BspBuildTarget = BspBuildTarget(
    displayName = Some(bspDisplayName),
    baseDirectory = Some(moduleDir.toNIO),
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
  private[mill] def bspBuildTargetData: Task[Option[(String, AnyRef)]] = Task.Anon { None }

}
