package mill.meta

import mill.*
import mill.api.daemon.internal.internal
import mill.api.{Discover, PathRef, Result, Task}
import mill.api.internal.RootModule
import mill.scalalib.{DepSyntax, ScalaModule}
import mill.javalib.api.{CompilationResult, Versions}
import mill.util.{BuildInfo, MainRootModule}
import mill.api.daemon.internal.CliImports
import mill.api.daemon.internal.MillBuildRootModuleApi

/**
 * Lightweight root module for script-only projects (no build.mill).
 * Contains non-build-specific configuration like BSP ignores, CLI imports, and runtime deps.
 */
@internal
trait BootstrapRootModule()(using
    rootModuleInfo: RootModule.Info
) extends ScalaModule with MillBuildRootModuleApi with mill.util.MainModule {

  def bspScriptIgnoreAll: T[Seq[String]] = bspScriptIgnoreDefault() ++ bspScriptIgnore()

  /**
   * Default set of BSP ignores, meant to catch the common case of `.java`, `.scala`, or `.kt`
   * files that definitely aren't scripts, but for some reason aren't recognized as being in
   * a module's `def sources` task (e.g. maybe module import failed or something)
   */
  def bspScriptIgnoreDefault: T[Seq[String]] = Seq(
    "**/src/",
    "**/src-*/",
    "**/resources/",
    "**/out/",
    "**/.bsp/mill-bsp-out/",
    "**/target/"
  )

  def bspScriptIgnore: T[Seq[String]] = Nil

  override def bspDisplayName0: String = rootModuleInfo
    .projectRoot
    .relativeTo(rootModuleInfo.topLevelProjectRoot)
    .segments
    .++(super.bspDisplayName0.split("/"))
    .mkString("/")

  override def scalaVersion: T[String] = BuildInfo.scalaVersion

  def cliImports: T[Seq[String]] = Task.Input {
    val imports = CliImports.value
    if (imports.nonEmpty) {
      Task.log.debug(s"Using cli-provided runtime imports: ${imports.mkString(", ")}")
    }
    imports
  }

  override def mandatoryMvnDeps = Task {
    Seq(
      mvn"com.lihaoyi::mill-libs:${Versions.millVersion}",
      mvn"com.lihaoyi::mill-runner-autooverride-api:${Versions.millVersion}"
    )
  }

  override def runMvnDeps = Task {
    val imports = cliImports()
    val ivyImports = imports.map {
      // compat with older Mill-versions
      case s"ivy:$rest" => rest
      case s"mvn:$rest" => rest
      case rest => rest
    }
    MillIvy.processMillMvnDepsignature(ivyImports).map(mill.scalalib.Dep.parse) ++
      // Needed at runtime to instantiate a `mill.eval.EvaluatorImpl` in the `build.mill`,
      // classloader but should not be available for users to compile against
      Seq(mvn"com.lihaoyi::mill-core-eval:${Versions.millVersion}")
  }

  override def platformSuffix: T[String] = s"_mill${BuildInfo.millBinPlatform}"

  // using non-platform dependencies is usually ok
  protected def resolvedDepsWarnNonPlatform: T[Boolean] = false

  def millVersion: T[String] = Task.Input { BuildInfo.millVersion }

  def millDiscover: Discover
}

object BootstrapRootModule {

  /**
   * Lightweight bootstrap module for script-only projects (no build.mill).
   * Script modules are discovered and instantiated separately at runtime.
   */
  class Instance()(using rootModuleInfo: RootModule.Info)
      extends MainRootModule() with BootstrapRootModule() {
    override lazy val millDiscover = Discover[this.type]

    // For script-only projects, we don't compile anything in the root module
    override def sources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

    // Return an empty compilation result since there are no sources
    override def compile: T[CompilationResult] = Task {
      val emptyClasses = Task.dest / "classes"
      os.makeDir.all(emptyClasses)
      Result.Success(CompilationResult(Task.dest / "zinc", PathRef(emptyClasses)))
    }
  }
}
