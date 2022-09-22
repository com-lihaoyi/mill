package mill.scalalib.bsp

import ammonite.runtime.SpecialClassLoader
import mill.api.{Loose, PathRef, internal}
import mill.define.{BaseModule, Input, Sources, Target, Task}
import mill.scalalib.api.CompilationResult
import mill.scalalib.buildfile.{MillBuildModule, MillSetupScannerModule}
import mill.scalalib.internal.ModuleUtils
import mill.scalalib.{Dep, DepSyntax, ScalaModule}
import mill.{Agg, BuildInfo, Module, T}
import upickle.default.{ReadWriter, macroRW}

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

/**
 * Synthetic module representing the mill-build project itself in a BSP context.
 * @param rootModule
 * @param outerCtx0
 */
@internal
trait MillBuildTarget
    extends ScalaModule
    with MillSetupScannerModule {
  protected def rootModule: BaseModule
  override def millSourcePath: os.Path = rootModule.millSourcePath
  override def scalaVersion: T[String] = BuildInfo.scalaVersion

  protected def millEmbeddedDeps: Input[Seq[String]] = T.input {
    BuildInfo.millEmbeddedDeps
  }

  override def compileIvyDeps: T[Agg[Dep]] = T {
    Agg.from(millEmbeddedDeps().map(d => parseDeps(d)))
  }

  override def ivyDeps: T[Agg[Dep]] = T {
    val deps = parsedMillSetup().ivyDeps
    Agg.from(deps.map(d => parseDeps(d)))
  }

  /**
   * We need to add all resources from Ammonites cache,
   * which typically also include resolved `ivy`-imports and compiled `$file`-imports.
   */
  override def unmanagedClasspath: T[Agg[PathRef]] = T {
    super.unmanagedClasspath() ++ (
      rootModule.getClass.getClassLoader match {
        case cl: SpecialClassLoader =>
          cl.allJars.map(url => PathRef(os.Path(java.nio.file.Paths.get(url.toURI))))
        case _ => Seq()
      }
    )
  }

  override def sources: Sources = T.sources {
    val optsFileName =
      T.env.get("MILL_JVM_OPTS_PATH").filter(!_.isEmpty).getOrElse(".mill-jvm-opts")

    Seq(
      PathRef(millSourcePath / ".mill-version"),
      PathRef(millSourcePath / optsFileName),
      buildScFile()
    ) ++ includedSourceFiles()
  }

  override def allSourceFiles: T[Seq[PathRef]] = T {
    findSources(sources().map(_.path))
  }

  protected def findSources(paths: Seq[os.Path], excludes: Seq[os.Path] = Seq()): Seq[PathRef] = {
    def isHiddenFile(path: os.Path) = path.last.startsWith(".")
    (for {
      root <- paths
      if os.exists(root) && !excludes.exists(excl => root.startsWith(excl))
      path <- if (os.isDir(root)) os.walk(root) else Seq(root)
      if os.isFile(path) && ((path.ext == "sc") && !isHiddenFile(path))
    } yield PathRef(path)).distinct
  }

  override def scalacPluginIvyDeps: Target[Loose.Agg[Dep]] = T{
    super.scalacPluginIvyDeps() ++ Agg(Deps.millModuledefs)
  }

  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    displayName = Some("mill-build"),
    baseDirectory = Some(rootModule.millSourcePath),
    languageIds = Seq(BspModule.LanguageId.Scala),
    canRun = false,
//    canCompile = false,
    canTest = false,
    canDebug = false,
    tags = Seq(BspModule.Tag.Library, BspModule.Tag.Application)
  )
//  override def compile: T[CompilationResult] = T {
//    T.log.errorStream.println(s"compile: ${T.dest}")
//    os.write(T.dest / "dummy", "")
//    os.makeDir(T.dest / "classes")
//    CompilationResult(T.dest / "dummy", PathRef(T.dest / "classes"))
//  }

  override def semanticDbData: T[PathRef] = T {
    T.log.errorStream.println(s"semanticDbData: ${T.dest}")
    PathRef(T.dest)
  }

  /** Used in BSP IntelliJ, which can only work with directories */
  def dummySources: Sources = T.sources(T.dest)
}
