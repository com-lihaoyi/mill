package mill.kotlinlib.ktfmt

import mill._
import mill.api.{PathRef}
import mill.api.{Discover, ExternalModule}
import mill.kotlinlib.{DepSyntax, KotlinModule, Versions}
import mill.util.Tasks
import mill.javalib.JavaModule
import mill.util.Jvm

trait KtfmtBaseModule extends JavaModule {

  /**
   * Classpath for running Ktfmt.
   */
  def ktfmtClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"com.facebook:ktfmt:${ktfmtVersion()}"),
      resolutionParamsMapOpt = Some(KotlinModule.addJvmVariantAttributes)
    )
  }

  /**
   * Ktfmt version.
   */
  def ktfmtVersion: T[String] = Task {
    Versions.ktfmtVersion
  }

  /**
   * Additional arguments for Ktfmt. Check
   * [[https://github.com/facebook/ktfmt/blob/main/core/src/main/java/com/facebook/ktfmt/cli/ParsedArgs.kt#L51 available options]].
   */
  def ktfmtOptions: T[Seq[String]] = Task {
    Seq.empty[String]
  }
}

/**
 * Performs formatting checks on Kotlin source files using [[https://github.com/facebook/ktfmt Ktfmt]].
 */
trait KtfmtModule extends KtfmtBaseModule {

  /**
   * Runs [[https://github.com/facebook/ktfmt Ktfmt]]
   *
   * @param ktfmtArgs arguments for the [[https://github.com/facebook/ktfmt Ktfmt]].
   * @param sources list of sources to run  the tool on. If not provided, default module sources will be taken.
   */
  def ktfmt(
      @mainargs.arg ktfmtArgs: KtfmtArgs,
      @mainargs.arg(positional = true) sources: Tasks[Seq[PathRef]] =
        Tasks.resolveMainDefault("__.sources")
  ): Command[Unit] = Task.Command {
    val _sources: Seq[PathRef] = if (sources.value.isEmpty) {
      this.sources()
    } else Task.sequence(sources.value)().flatten
    KtfmtModule.ktfmtAction(
      ktfmtArgs.style,
      ktfmtArgs.format,
      ktfmtArgs.removeUnusedImports,
      _sources,
      ktfmtClasspath(),
      ktfmtOptions()
    )
  }
}

object KtfmtModule extends ExternalModule with KtfmtBaseModule with DefaultTaskModule {

  lazy val millDiscover = Discover[this.type]

  override def defaultTask(): String = "formatAll"

  /**
   * Runs [[https://github.com/facebook/ktfmt Ktfmt]].
   *
   * @param ktfmtArgs formatting arguments
   * @param sources list of [[KotlinModule]] to process
   */
  def formatAll(
      @mainargs.arg ktfmtArgs: KtfmtArgs,
      @mainargs.arg(positional = true) sources: Tasks[Seq[PathRef]] = Tasks.resolveMainDefault("__.sources")
  ): Command[Unit] = Task.Command {
    val _sources = Task.sequence(sources.value)().iterator.flatten
    ktfmtAction(
      ktfmtArgs.style,
      ktfmtArgs.format,
      ktfmtArgs.removeUnusedImports,
      _sources,
      ktfmtClasspath(),
      ktfmtOptions()
    )
  }

  private def ktfmtAction(
      style: String,
      format: Boolean,
      removeUnusedImports: Boolean,
      sources: IterableOnce[PathRef],
      classPath: Seq[PathRef],
      options: Seq[String]
  )(using ctx: mill.api.TaskCtx): Unit = {

    ctx.log.info("running ktfmt ...")

    val args = Seq.newBuilder[String]
    args ++= options
    args += (style match {
      case "kotlin" => "--kotlinlang-style"
      case "google" => "--google-style"
      case "meta" => "--meta-style"
      case _ => throw new IllegalArgumentException(s"Unknown style ktfmt style: $style")
    })
    if (!format) {
      args += "--dry-run"
    }
    if (!removeUnusedImports) {
      args += "--do-not-remove-unused-imports"
    }
    if (!format) args += "--set-exit-if-changed"
    args ++= sources.iterator.map(_.path.toString())

    val exitCode = Jvm.callProcess(
      mainClass = "com.facebook.ktfmt.cli.Main",
      classPath = classPath.map(_.path).toVector,
      mainArgs = args.result(),
      cwd = moduleDir, // allow passing relative paths for sources like src/a/b
      stdin = os.Inherit,
      stdout = os.Inherit,
      check = false
    ).exitCode

    if (exitCode == 0) {} // do nothing
    else {
      throw new RuntimeException(s"ktfmt exited abnormally with exit code = $exitCode")
    }
  }
}
