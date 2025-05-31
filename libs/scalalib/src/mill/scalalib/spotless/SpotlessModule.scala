package mill.scalalib.spotless

import mainargs.{Flag, Leftover}
import mill.api.SelectMode
import mill.define.*
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
   *
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

  def defaultCommandName() = "spotless"

  /**
   * Applies [[SpotlessWorkerModule.formats]] on dynamically evaluated `sources`.
   * @param check If set, the command fails on format errors. Otherwise, formatting is fixed.
   */
  def spotless(
      check: Flag,
      evaluator: Evaluator,
      sources: Leftover[String]
  ): Task.Command[Unit] = {
    val tasks = evaluator.resolveTasks(
      scriptArgs = if sources.value.isEmpty then Seq("__.sources") else sources.value,
      selectMode = SelectMode.Separated,
      allowPositionalCommandArgs = true
    ).get.asInstanceOf[Seq[Task[Seq[PathRef]]]]
    Task.Command() {
      spotlessWorker().worker()
        .format(files(Task.sequence(tasks)().flatten), check.value)
    }
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
