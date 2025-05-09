package mill.javalib.spotless

import mainargs.{Flag, arg}
import mill.api.Result
import mill.define.*
import mill.javalib.JavaModule
import mill.util.Tasks
import mill.util.TokenReaders.given

/**
 * Enables support for [[https://github.com/diffplug/spotless Spotless]] formatter.
 */
trait SpotlessModule extends JavaModule {

  /**
   * Applies Spotless on [[spotlessSources]].
   *
   * @param check error if the code is not formatted
   */
  def spotless(check: Flag): Command[Unit] = Task.Command {
    SpotlessWorkerModule.worker()
      .format(
        spotlessSources(),
        check.value,
        spotlessConfig(),
        spotlessConfigRoots(),
        defaultResolver()
      )
  }

  /**
   * Path to Spotless configuration file.
   */
  // does not have to be Task.Source since worker already checks config file sig
  def spotlessConfig: Task[PathRef] = Task {
    val name = "spotless-config.json"
    spotlessConfigRoots().iterator
      .map(_.path / name)
      .find(os.exists) match
      case Some(path) => Result.Success(PathRef(path))
      case None => Result.Failure(s"$name not found")
  }

  /**
   * Locations to resolve relative paths in Spotless configuration file.
   */
  def spotlessConfigRoots: Task[Seq[PathRef]] = Task.Sources(moduleDir, Task.workspace)

  /**
   * Source files/folders to format using Spotless.
   */
  def spotlessSources: Task[Seq[PathRef]] = Task {
    sources()
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    SpotlessWorkerModule.worker()
      .prepare(spotlessConfig(), spotlessConfigRoots(), defaultResolver())
    super.prepareOffline(all)()
  }
}

object SpotlessModule extends ExternalModule with TaskModule with SpotlessModule {

  def formatAll(
      check: Flag,
      @arg(positional = true)
      sources: Tasks[Seq[PathRef]] = Tasks.resolveMainDefault("__.sources")
  ): Command[Unit] = Task.Command {
    SpotlessWorkerModule.worker()
      .format(
        Task.sequence(sources.value)().flatten,
        check.value,
        spotlessConfig(),
        spotlessConfigRoots(),
        defaultResolver()
      )
  }

  def defaultCommandName() = "formatAll"

  lazy val millDiscover = Discover[this.type]
}
