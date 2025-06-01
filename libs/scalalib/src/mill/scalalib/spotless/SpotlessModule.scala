package mill.scalalib.spotless

import mainargs.Flag
import mill.constants.OutFiles
import mill.define.*
import mill.util.Tasks
import mill.util.TokenReaders.given

/**
 * Enables support for formatting files using [[https://github.com/diffplug/spotless Spotless]].
 */
@mill.api.experimental // see notes in package object
trait SpotlessModule extends WithSpotlessWorker {

  /**
   * Files/folders to format using Spotless.  Defaults to "src" sub-folder.
   */
  def spotlessTargets: Task[Seq[PathRef]] = Task.Sources("src")

  /**
   * Checks/fixes formatting in [[spotlessTargets]].
   * @param check If set, the command fails on format errors. Otherwise, formatting is fixed.
   */
  def spotless(check: Flag) = Task.Command {
    spotlessWorker().worker().format(files(spotlessTargets()), check.value)
  }
}
@mill.api.experimental // see notes in package object
object SpotlessModule extends ExternalModule, TaskModule, WithSpotlessWorker {

  lazy val millDiscover = Discover[this.type]

  def defaultCommandName() = "format"

  /**
   * Checks/fixes formatting in `targets`.
   *
   * @param targets Files/folders to format.
   * @param check If set, the command fails on format errors. Otherwise, formatting is fixed.
   */
  def format(
      @mainargs.arg(positional = true)
      targets: Tasks[Seq[PathRef]] = Tasks.resolveMainDefault("__.sources"),
      check: Flag
  ) = Task.Command() {
    spotlessWorker().worker().format(files(Task.sequence(targets.value)().flatten), check.value)
  }

  /**
   * Checks/fixes formatting in all files in the workspace.
   *
   * @param check If set, the command fails on format errors. Otherwise, formatting is fixed.
   * @param skip Additional paths to skip. Mill `out` and ".git" are always skipped and can be omitted.
   */
  def formatAll(check: Flag, skip: Seq[String]) = Task.Command {
    spotlessWorker().worker().format(
      files(
        Seq(PathRef(Task.workspace)),
        (Seq(OutFiles.out, ".git") ++ skip).map(s => Task.workspace / os.SubPath(s))
      ),
      check.value
    )
  }

  /**
   * Checks/fixes formatting in files that differ in 2 Git trees.
   * @param fromRev Revision to compare from.
   * @param toRev Revision to compare. When empty, the working tree is compared.
   * @param check If set, an error is raised on format errors. Otherwise, formatting is fixed.
   */
  def ratchet(
      @mainargs.arg(positional = true)
      fromRev: String = "HEAD",
      @mainargs.arg(positional = true)
      toRev: Option[String],
      check: Flag
  ) = Task.Command {
    spotlessWorker().worker().ratchet(fromRev, toRev, check.value)
  }
}

private def files(targets: Seq[PathRef], skip: Seq[os.Path] = Seq()): Seq[PathRef] = {
  targets.flatMap { ref =>
    if os.isDir(ref.path) then
      os.walk.stream(ref.path, skip = skip.contains)
        .collect:
          case path if os.isFile(path) => PathRef(path)
        .toSeq
    else if os.isFile(ref.path) then Seq(ref)
    else Seq()
  }
}
