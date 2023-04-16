package mill.scalalib.bsp

import mill.api.{Loose, PathRef, internal}
import mill.define.{BaseModule, Discover, ExternalModule, Sources, Target, Task}
import mill.scalalib.api.CompilationResult
import mill.scalalib.bsp.BuildScAwareness.{IncludedDep, IncludedFile}
import mill.scalalib.{Dep, DepSyntax, ScalaModule}
import mill.{Agg, BuildInfo, T}
import os.Path

/**
 * Synthetic module representing the mill-build project itself in a BSP context.
 */
@internal
trait MillBuildModule
    extends ScalaModule {
  protected def projectPath: os.Path

  /** The Mill project directory. */
  override def millSourcePath: os.Path = projectPath

  // The buildfile and single source of truth
  def buildScFile = T.sources(projectPath / "build.sc")

  private def buildScImports: T[Seq[BuildScAwareness.Included]] = T {
    // we depend on buildScFile, to recompute whenever build.sc changes
    val imports = buildScFile().flatMap { buildSc =>
      BuildScAwareness.parseAmmoniteImports(buildSc.path)
    }
    T.log.errorStream.println(s"buildScImports: ${imports}")
    imports
  }

  override def scalaVersion: T[String] = BuildInfo.scalaVersion

  override def platformSuffix: T[String] = T {
    BuildInfo.millBinPlatform
  }

  override def compileIvyDeps: T[Agg[Dep]] = T {
    val deps = Agg.from(BuildInfo.millEmbeddedDeps.split(",").map(d => ivy"${d}".forceVersion()))
    T.log.errorStream.println(s"compileIvyDeps: ${deps}")
    deps
  }

  override def ivyDeps: T[Agg[Dep]] = T {
    val deps = buildScImports().collect {
      case d: IncludedDep =>
//        val Seq(dep) = MillIvy.processMillIvyDepSignature(Seq(d.dep))
        val Seq(dep) = Seq(d.dep)
        ivy"${dep}"
    }
    T.log.errorStream.println(s"ivyDeps: ${deps}")
    Agg.from(deps)
  }

  override def scalacPluginIvyDeps: Target[Loose.Agg[Dep]] = T {
    val deps = BuildInfo.millScalacPluginDeps.map(d => ivy"${d}")
    T.log.errorStream.println(s"scalacPluginIvyDeps: ${deps}")
    deps
  }

  /**
   * We need to add all resources from Ammonites cache,
   * which typically also include resolved `ivy`-imports and compiled `$file`-imports.
   */
//  override def unmanagedClasspath: T[Agg[PathRef]] = T {
//    super.unmanagedClasspath() ++ (
//      rootModule.getClass.getClassLoader match {
//        case cl: SpecialClassLoader =>
//          cl.allJars.map(url => PathRef(os.Path(java.nio.file.Paths.get(url.toURI))))
//        case _ => Seq()
//      }
//    )
//  }

  // We need to be careful here to not include the out/ directory
  override def sources: Sources = T.sources {
    val sources = allSourceFiles()
    T.log.errorStream.println(s"sources: ${sources}")
    sources
  }

  override def allSourceFiles: T[Seq[PathRef]] = T {
    val buildSc = buildScFile()
    val includedFiles = buildScImports().collect {
      case i: IncludedFile => i.file
    }.map(p => PathRef(p))
    val all = buildSc ++ includedFiles
    T.log.errorStream.println(s"allSourceFiles: ${all}")
    all
  }

  override def compileResources: Sources = T.sources()
  override def resources: Sources = T.sources()

  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    displayName = Some("mill-build"),
    baseDirectory = Some(projectPath),
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

/**
 * Just for testing purposes.
 */
@internal
object MillBuildModule extends ExternalModule with MillBuildModule {
  lazy val millDiscover: Discover[this.type] = Discover[this.type]

  override protected def projectPath: Path = super[ExternalModule].millSourcePath
}
