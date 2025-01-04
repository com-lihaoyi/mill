package mill.pythonlib

import mill._

/**
 * Linting and formatting functionality provided by [ruff](https://docs.astral.sh/ruff/).
 */
trait RuffModule extends PythonModule {

  override def pythonToolDeps = Task {
    super.pythonToolDeps() ++ Seq("ruff>=0.8.1")
  }

  /**
   * Global command line options to pass to ruff.
   *
   * You can use this to specify config files, for example
   * ```
   * def ruffOptions = Seq("--config", (millSourcePath / "ruff.toml").toString)
   * ```
   */
  def ruffOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Run `ruff format` on all the source files of this module.
   *
   * You can supply any additional args that ruff understands. For example:
   * - only check format of sources, but don't actually format: `--check`
   * - see format diff: `--diff`
   */
  def ruffFormat(args: String*): Command[Unit] = Task.Command {
    runner().run(
      // format: off
      (
        "-m", "ruff",
        "format",
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
    runner().run(
      // format: off
      (
        "-m", "ruff",
        "check",
        "--cache-dir", T.dest / "cache",
        ruffOptions(),
        args,
        sources().map(_.path)
      ),
      // format: on
      workingDir = Task.dest
    )
  }

}
