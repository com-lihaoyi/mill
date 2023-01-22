package mill.scalalib.bsp

import ammonite.runtime.SpecialClassLoader
import mill.api.{PathRef, internal}
import mill.define.{BaseModule, Sources}
import mill.scalalib.api.CompilationResult
import mill.scalalib.{Dep, DepSyntax, ScalaModule}
import mill.{Agg, BuildInfo, T}

/**
 * Synthetic module representing the mill-build project itself in a BSP context.
 *
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
