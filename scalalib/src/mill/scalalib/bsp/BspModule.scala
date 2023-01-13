package mill.scalalib.bsp

import ammonite.runtime.SpecialClassLoader
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
)

case class BspBuildTargetId(id: BspUri)

case class BspUri(uri: String)

object BspUri {
  def apply(path: os.Path): BspUri = BspUri(path.toNIO.toUri.toString)
}

case class JvmBuildTarget(
    javaHome: Option[BspUri],
    javaVersion: Option[String]
)

object JvmBuildTarget {
  val dataKind: String = "jvm"
}

case class ScalaBuildTarget(
    /** The Scala organization that is used for a target. */
    scalaOrganization: String,
    /** The scala version to compile this target */
    scalaVersion: String,
    /**
     * The binary version of scalaVersion.
     * For example, 2.12 if scalaVersion is 2.12.4.
     */
    scalaBinaryVersion: String,
    /** The target platform for this target */
    platform: ScalaPlatform,
    /** A sequence of Scala jars such as scala-library, scala-compiler and scala-reflect. */
    jars: Seq[String],
    /** The jvm build target describing jdk to be used */
    jvmBuildTarget: Option[JvmBuildTarget]
)

object ScalaBuildTarget {
  val dataKind: String = "scala"
}

abstract class ScalaPlatform(val number: Int)
object ScalaPlatform {
  case object JVM extends ScalaPlatform(1)
  case object JS extends ScalaPlatform(2)
  case object Native extends ScalaPlatform(3)
}

/**
 * Synthetic module representing the mill-build project itself in a BSP context.
 * @param rootModule
 * @param outerCtx0
 */
@internal
trait MillBuildTarget
    extends ScalaModule {
  protected def rootModule: BaseModule
  override def millSourcePath: os.Path = rootModule.millSourcePath
  override def scalaVersion: T[String] = BuildInfo.scalaVersion
  override def compileIvyDeps: T[Agg[Dep]] = T {
    T.log.errorStream.println(s"ivyDeps: ${T.dest}")
    Agg.from(BuildInfo.millEmbeddedDeps.map(d => ivy"${d}"))
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

  // The buildfile and single source of truth
  def buildScFile = T.source(millSourcePath / "build.sc")
  def ammoniteFiles = T {
    T.log.errorStream.println(s"ammoniteFiles: ${T.dest}")
    // we depend on buildScFile, to recompute whenever build.sc changes
    findSources(Seq(millSourcePath), excludes = Seq(millSourcePath / "out"))
  }
  // We need to be careful here to not include the out/ directory
  override def sources: Sources = T.sources {
    T.log.errorStream.println(s"sources: ${T.dest}")
    val sources = ammoniteFiles()
    T.log.errorStream.println(s"sources: ${sources}")
    sources
  }
  override def allSourceFiles: T[Seq[PathRef]] = T {
    findSources(sources().map(_.path))
  }
  def findSources(paths: Seq[os.Path], excludes: Seq[os.Path] = Seq()): Seq[PathRef] = {
    def isHiddenFile(path: os.Path) = path.last.startsWith(".")
    (for {
      root <- paths
      if os.exists(root) && !excludes.exists(excl => root.startsWith(excl))
      path <- if (os.isDir(root)) os.walk(root) else Seq(root)
      if os.isFile(path) && ((path.ext == "sc") && !isHiddenFile(path))
    } yield PathRef(path)).distinct
  }
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    displayName = Some("mill-build"),
    baseDirectory = Some(rootModule.millSourcePath),
    languageIds = Seq(BspModule.LanguageId.Scala),
    canRun = false,
    canCompile = false,
    canTest = false,
    canDebug = false,
    tags = Seq(BspModule.Tag.Library, BspModule.Tag.Application)
  )
  override def compile: T[CompilationResult] = T {
    T.log.errorStream.println(s"compile: ${T.dest}")
    os.write(T.dest / "dummy", "")
    os.makeDir(T.dest / "classes")
    CompilationResult(T.dest / "dummy", PathRef(T.dest / "classes"))
  }

  override def semanticDbData: T[PathRef] = T {
    T.log.errorStream.println(s"semanticDbData: ${T.dest}")
    PathRef(T.dest)
  }

  /** Used in BSP IntelliJ, which can only work with directories */
  def dummySources: Sources = T.sources(T.dest)
}
