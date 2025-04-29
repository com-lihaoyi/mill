package mill.javalib.spotless

import mainargs.{Flag, arg}
import mill.api.Result
import mill.define.*
import mill.javalib.{Dep, JavaModule}
import mill.util.TokenReaders.given
import mill.util.{Jvm, Tasks}

/**
 * Adds support for [[https://github.com/diffplug/spotless Spotless]] formatter/linter.
 */
trait SpotlessModule extends JavaModule {

  /**
   * Path to Spotless configuration file containing the [[https://github.com/diffplug/spotless/tree/main?tab=readme-ov-file#current-feature-matrix FormaaterStep]]s to apply.
   */
  def spotlessConfigFile: Task[PathRef] = Task.Source {
    Task.workspace / "spotless-config.json"
  }

  def spotlessClasspath: Task[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-libs-spotless")))
  }

  /**
   * Runs Spotless on module [[sources]].
   * @param opts Additional options:
   *             - `--check`: error on format/lint errors
   */
  def spotless(opts: String*): Command[Unit] = Task.Command {
    runSpotless(opts, spotlessConfigFile().path, sources(), spotlessClasspath())
  }

  private[spotless] def runSpotless(
      opts: Seq[String],
      configFile: os.Path,
      sources: Seq[PathRef],
      classpath: Seq[PathRef]
  )(implicit ctx: TaskCtx) = {
    val args = opts ++
      Seq(configFile.toString) ++
      sources.map(_.path.toString)
    Jvm.callProcess(
      mainClass = "mill.spotless.SpotlessMain",
      mainArgs = args,
      classPath = classpath.map(_.path),
      stdout = os.Inherit
    )
  }
}

object SpotlessModule extends ExternalModule with TaskModule with SpotlessModule {

  def formatAll(
      check: Flag,
      @arg(doc = "config file path relative to workspace")
      config: Option[String],
      @arg(positional = true)
      sources: Tasks[Seq[PathRef]] = Tasks.resolveMainDefault("__.sources")
  ): Command[Unit] = Task.Command {
    val opts = if check.value then Seq("--check") else Seq()
    val configFile = config.fold(spotlessConfigFile().path)(rel => Task.workspace / rel)
    runSpotless(
      opts,
      configFile,
      Task.sequence(sources.value)().flatten,
      spotlessClasspath()
    )
  }

  def defaultCommandName() = "formatAll"

  lazy val millDiscover = Discover[this.type]
}
