package mill.scalalib.bsp

import mill.define.{BaseModule, Segments, Task}
import mill.scalalib.JavaModule
import mill.{Module, T}
import os.Path

trait BspModule extends Module {
  import BspModule._

  def bspBuildTarget: BspBuildTarget = BspBuildTarget(
    displayName = Some(millModuleSegments.render),
    baseDirectory = Some(millSourcePath),
    tags = Seq(),
    languageIds = Seq(Tag.Library, Tag.Application),
    canCompile = false,
    canTest = false,
    canRun = false,
    canDebug = false,
  )

  def bspBuildTargetData: Task[Option[(String, AnyRef)]] = T.task { None }

}

object BspModule {
  object LanguageId {
    val Java = "java"
    val Scala = "scala"
  }

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

case class BspBuildTarget(
    displayName: Option[String],
    baseDirectory: Option[os.Path],
    tags: Seq[String],
    languageIds: Seq[String],
    canCompile: Boolean,
    canTest: Boolean,
    canRun: Boolean,
    canDebug: Boolean,
//    data: (Option[String], Option[Any]) = (None, None)
)

case class BspBuildTargetId(id: BspUri)

case class BspUri(uri: String)

object BspUri {
  def apply(path: os.Path): BspUri = BspUri(path.toNIO.toUri.toString)
}

class MillBuildTarget(rootModule: BaseModule)(implicit outerCtx0: mill.define.Ctx)
    extends BspModule {
  override def millSourcePath: Path = rootModule.millSourcePath
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    displayName = Some("mill-build"),
    baseDirectory = Some(rootModule.millSourcePath),
    canRun = false,
    canCompile = false,
    canTest = false,
    canDebug = false,
    tags = Seq(BspModule.Tag.Library, BspModule.Tag.Application)
  )
}
