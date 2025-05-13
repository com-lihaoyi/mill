package mill.scalalib.spotless

import mainargs.{Flag, Leftover}
import mill.api.SelectMode
import mill.define.*
import mill.scalalib.CoursierModule
import mill.util.TokenReaders.given

/**
 * Enables support for formatting files using [[https://github.com/diffplug/spotless Spotless]].
 *
 * @note Artifacts for third-party formatters used by Spotless are resolved using [[CoursierModule.defaultResolver]].
 */
@mill.api.experimental
trait SpotlessModule extends CoursierModule, WithSpotlessWorker {

  /**
   * Run Spotless formatters on [[spotlessSources]].
   * @param check control flag for action to take when format errors are found
   *              - if set, the command fails
   *              - otherwise, formatting is fixed
   */
  def spotless(check: Flag): Task.Command[Unit] = Task.Command {
    spotlessWorker()
      .worker()
      .format(filesToFormat(spotlessSources()), check.value)
  }

  /**
   * Files/folders to format using Spotless.
   */
  def spotlessSources: Task[Seq[PathRef]]
}
@mill.api.experimental
object SpotlessModule extends ExternalModule, TaskModule, WithSpotlessWorker {

  lazy val millDiscover = Discover[this.type]

  def defaultCommandName() = "spotless"

  /**
   * Run Spotless formatters on dynamically evaluated `sources`.
   * @param check control flag for action to take when format errors are found
   *              - if set, the command fails
   *              - otherwise, formatting is fixed
   */
  def spotless(evaluator: Evaluator, sources: Leftover[String], check: Flag): Task.Command[Unit] = {
    val tasks = evaluator.resolveTasks(
      scriptArgs = if sources.value.isEmpty then Seq("__.sources") else sources.value,
      selectMode = SelectMode.Separated
    ).get.asInstanceOf[Seq[Task[Seq[PathRef]]]]
    Task.Command() {
      spotlessWorker()
        .worker()
        .format(filesToFormat(Task.sequence(tasks)().flatten), check.value)
    }
  }
}

private def filesToFormat(sources: Seq[PathRef]): Seq[PathRef] = {
  sources.flatMap { ref =>
    if os.isDir(ref.path) then
      os.walk.stream(ref.path)
        .collect:
          case path if os.isFile(path) => PathRef(path)
        .toSeq
    else if os.isFile(ref.path) then Seq(ref)
    else Seq()
  }
}
