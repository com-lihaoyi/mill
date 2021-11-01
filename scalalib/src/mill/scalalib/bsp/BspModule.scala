package mill.scalalib.bsp

import mill.api.{PathRef, internal}
import mill.define.{BaseModule, Sources, Task}
import mill.scalalib.{Dep, DepSyntax, ScalaModule}
import mill.{Agg, BuildInfo, Module, T}

trait BspModule extends Module {
  import BspModule._

  /** Use to fill most fields of `BuildTarget`. */
  @internal
  def bspBuildTarget: BspBuildTarget = BspBuildTarget(
    displayName = Some(millModuleSegments.render),
    baseDirectory = Some(millSourcePath),
    tags = Seq(),
    languageIds = Seq(Tag.Library, Tag.Application),
    canCompile = false,
    canTest = false,
    canRun = false,
    canDebug = false
  )

  /** Use to populate the `BuildTarget.{dataKind,data}` fields. */
  @internal
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
    canDebug: Boolean
//    data: (Option[String], Option[Any]) = (None, None)
)

case class BspBuildTargetId(id: BspUri)

case class BspUri(uri: String)

object BspUri {
  def apply(path: os.Path): BspUri = BspUri(path.toNIO.toUri.toString)
}

class MillBuildTarget(rootModule: BaseModule)(implicit outerCtx0: mill.define.Ctx)
    extends ScalaModule {
  override def millSourcePath: os.Path = rootModule.millSourcePath
  override def scalaVersion: T[String] = BuildInfo.scalaVersion
  override def ivyDeps: T[Agg[Dep]] = T {
    Agg.from(BuildInfo.millEmbeddedDeps.map(d => ivy"${d}"))
  }
  override def sources: Sources = T.sources(millSourcePath)
  override def allSourceFiles: T[Seq[PathRef]] = T {
    def isHiddenFile(path: os.Path) = path.last.startsWith(".")
    for {
      root <- allSources()
      if os.exists(root.path)
      path <- if (os.isDir(root.path)) os.walk(root.path) else Seq(root.path)
      if os.isFile(path) && ((path.ext == "sc") && !isHiddenFile(path))
    } yield PathRef(path)
  }
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
