package mill.pythonlib

import mill.*
import mill.api.{Args, ExternalModule, Discover}
import mill.util.Tasks

/**
 * Linting and formatting functionality provided by [ruff](https://docs.astral.sh/ruff/).
 */
trait RuffModule extends PythonModule {

  /** The isolated Ruff tool environment managed and cached by uv. */
  def ruffTool: T[String] = Task { "ruff==0.16.10" }

  /**
   * Configuration file to use when running ruff. If this file does not exist,
   * ruff will use the default settings.
   */
  def ruffConfigFile: T[PathRef] = Task.Source("ruff.toml")

  /**
   * Global command line options to pass to ruff. These are passed in before any
   * command-supplied arguments.
   */
  def ruffOptions: T[Seq[String]] = Task { Seq.empty[String] }

  protected def configArgs: Task[Seq[String]] = Task.Anon {
    val cfg = ruffConfigFile()
    if (os.exists(cfg.path)) Seq("--config", cfg.path.toString) else Seq.empty[String]
  }

  /**
   * Run `ruff format` on all the source files of this module.
   *
   * You can supply any additional args that ruff understands. For example:
   *
   * - only check format of sources, but don't actually format: `--check`
   * - see format diff: `--diff`
   */
  def ruffFormat(args: String*): Command[Unit] = Task.Command {
    uvRunner().run(
      // format: off
      (
        "tool", "run", uvIndexArgs(), "--from", ruffTool(), "ruff",
        "format",
        configArgs(),
        ruffOptions(),
        args,
        sources().map(_.path)
      ),
      // format: on
      workingDir = Task.dest
    )
  }

  /**
   * Run `run check` on all the source files of this module.
   *
   * You can supply additional arguments that ruff understands, for example to
   * attempt to automatically fix any linting errors: `--fix`.
   */
  def ruffCheck(args: String*): Command[Unit] = Task.Command {
    uvRunner().run(
      // format: off
      (
        "tool", "run", uvIndexArgs(), "--from", ruffTool(), "ruff",
        "check",
        "--cache-dir", Task.dest / "cache",
        configArgs(),
        ruffOptions(),
        args,
        sources().map(_.path)
      ),
      // format: on
      workingDir = Task.dest
    )
  }

}

object RuffModule extends ExternalModule with RuffModule with DefaultTaskModule {

  override def defaultTask(): String = "formatAll"

  def formatAll(
      sources: Tasks[Seq[PathRef]] = Tasks.resolveMainDefault("__.sources"),
      @mainargs.arg(positional = true) ruffArgs: Args
  ): Command[Unit] = Task.Command {
    uvRunner().run(
      // format: off
      (
        "tool", "run", uvIndexArgs(), "--from", ruffTool(), "ruff",
        "format",
        configArgs(),
        ruffOptions(),
        ruffArgs.value,
        Task.sequence(sources.value)().flatten.map(_.path)
      ),
      // format: on
      workingDir = Task.dest
    )
  }

  def checkAll(
      sources: Tasks[Seq[PathRef]] = Tasks.resolveMainDefault("__.sources"),
      @mainargs.arg(positional = true) ruffArgs: Args
  ): Command[Unit] = Task.Command {
    uvRunner().run(
      // format: off
      (
        "tool", "run", uvIndexArgs(), "--from", ruffTool(), "ruff",
        "check",
        "--cache-dir", Task.dest / "cache",
        configArgs(),
        ruffOptions(),
        ruffArgs.value,
        Task.sequence(sources.value)().flatten.map(_.path)
      ),
      // format: on
      workingDir = Task.dest
    )
  }

  lazy val millDiscover = Discover[this.type]

}
