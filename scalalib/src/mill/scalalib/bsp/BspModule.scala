package mill.scalalib.bsp

import mill.define.{BaseModule, Segments}
import mill.scalalib.JavaModule
import mill.{Module, T}

trait BspModule extends Module {

  def bspBuildTarget: BspBuildTarget = BspBuildTarget(
//    id = BspBuildTargetId(BspUri(millOuterCtx.millSourcePath / millModuleSegments.parts)),
    displayName = Some(millModuleSegments.render),
    baseDirectory = Some(millSourcePath),
    tags = Seq(),
    languageIds = Seq(),
    canCompile = false,
    canTest = false,
    canRun = false,
    canDebug = false,
//    dependencies = Seq()
  )

}

object BspModule {
  object LanguageId {
    val Java = "java"
    val Scala = "scala"
  }
}

case class BspBuildTarget(
//    id: BspBuildTargetId,
    displayName: Option[String],
    baseDirectory: Option[os.Path],
    tags: Seq[String],
    languageIds: Seq[String],
    canCompile: Boolean,
    canTest: Boolean,
    canRun: Boolean,
    canDebug: Boolean,
//    dependencies: Seq[BspBuildTargetId],
    data: (Option[String], Option[Any]) = (None, None)
)

case class BspBuildTargetId(id: BspUri)

case class BspUri(uri: String)

object BspUri {
  def apply(path: os.Path): BspUri = BspUri(path.toNIO.toUri.toString)
}

class MillBuildTarget(rootModule: BaseModule)(implicit outerCtx0: mill.define.Ctx)
    extends BspModule {
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
//    id = BspBuildTargetId(BspUri(rootModule.millSourcePath)),
    displayName = Some("mill-build"),
    baseDirectory = Some(rootModule.millSourcePath),
    canRun = false,
    canCompile = false,
    canTest = false,
    canDebug = false
  )
}
