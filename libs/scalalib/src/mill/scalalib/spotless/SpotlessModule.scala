package mill.scalalib.spotless

import mainargs.Flag
import mill.define.*
import mill.util.Tasks
import mill.util.TokenReaders.given

/**
 * Enables support for formatting files using [[https://github.com/diffplug/spotless Spotless]].
 */
trait SpotlessModule extends WithSpotlessWorker {

  /**
   * Files/folders to format using Spotless.
   */
  def spotlessTargets: Task[Seq[PathRef]]

  /**
   * Applies [[SpotlessWorkerModule.formats]] on [[spotlessTargets]].
   * @param check If set, the command fails on format errors. Otherwise, formatting is fixed.
   */
  def spotless(check: Flag) = Task.Command {
    spotlessWorker()
      .worker()
      .format(files(spotlessTargets()), check.value)
  }
}
object SpotlessModule extends ExternalModule, TaskModule, WithSpotlessWorker {

  lazy val millDiscover = Discover[this.type]

  def defaultCommandName() = "format"

  /**
   * Applies [[SpotlessWorkerModule.formats]] on `targets`.
   * @param targets Files/folders to format.
   * @param check If set, the command fails on format errors. Otherwise, formatting is fixed.
   */
  def format(
      @mainargs.arg(positional = true)
      targets: Tasks[Seq[PathRef]] = Tasks.resolveMainDefault("__.sources"),
      check: Flag
  ) = Task.Command() {
    spotlessWorker().worker()
      .format(files(Task.sequence(targets.value)().flatten), check.value)
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
    spotlessWorker().worker()
      .ratchet(fromRev, toRev, check.value)

  }
}

private def files(targets: Seq[PathRef]): Seq[PathRef] = {
  targets.flatMap { ref =>
    if os.isDir(ref.path) then
      os.walk.stream(ref.path)
        .collect:
          case path if os.isFile(path) => PathRef(path)
        .toSeq
    else if os.isFile(ref.path) then Seq(ref)
    else Seq()
  }
}
