package mill.pythonlib

import mill._
import mill.define.ExternalModule
import mill.define.Discover
import mill.TaskModule

trait RuffModule extends PythonModule {

  override def pythonToolDeps = Task {
    super.pythonToolDeps() ++ Seq("ruff>=0.8.1")
  }

  /** Ad-hoc command to invoke ruff on the sources of this module.
   *
   * You'll need to supply any subcommand that ruff understands. For example:
   *
   * - format sources: ruff format
   * - check format of sources: ruff format --check
   * - see format diff: ruff format --diff
   * - find linting errors: ruff check
   * - automatically fix linting errors: ruff check --fix
   *
  */
  def ruff(args: String*) = Task.Command {
    runner().run(
      // format: off
      (
        "-m", "ruff",
        args,
        sources().map(_.path)
      ),
      // format: on
      workingDir = Task.dest
    )
  }

}

/**
 * Includes some common tasks to reformat and check all sources. This mimics ScalafmtModule, so that
 *
*/
object RuffModule extends ExternalModule with TaskModule {

  def defaultCommandName() = "reformatAll"

  lazy val millDiscover: Discover = Discover[this.type]

  def reformatAll


}

