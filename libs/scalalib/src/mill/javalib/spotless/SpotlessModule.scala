package mill.javalib.spotless

import mainargs.{Flag, arg}
import mill.api.Result
import mill.define.*
import mill.javalib.JavaModule
import mill.util.Tasks
import mill.util.TokenReaders.given

/**
 * Enables support for [[https://github.com/diffplug/spotless Spotless]] formatter.
 * @see [[spotless]]
 */
@mill.api.experimental
trait SpotlessModule extends JavaModule {

  /**
   * Formats [[spotlessSources]] using steps defined in [[spotlessConfig]].
   * Runtime artifacts for steps, if any, are resolved using [[defaultResolver]].
   * @param check error if format errors are found
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
   * Path to Spotless configuration file. Defaults to `spotless-config.json` under [[Task.workspace]].
   * References to relative paths in the configuration are resolved against [[spotlessConfigRoots]].
   */
  def spotlessConfig: Task[PathRef] = Task.Source(Task.workspace / "spotless-config.json")

  /**
   * Folders to resolve relative paths in [[spotlessConfig]] against.
   * Defaults to folders from [[moduleDir]] to [[Task.workspace]],
   * which allows for overriding referenced files like `.scalafmt.conf` in nested modules.
   */
  def spotlessConfigRoots: Task[Seq[PathRef]] = Task.Sources(
    moduleDir.relativeTo(Task.workspace).segments
      .foldLeft(Seq(moduleDir))((roots, up) => roots :+ (roots.last / up))
      .map(Result.Success(_))*
  )

  /**
   * Source files/folders to format using Spotless. Defaults to [[sources]].
   */
  def spotlessSources: Task[Seq[PathRef]] = Task {
    sources()
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    super.prepareOffline(all)() ++
      SpotlessWorkerModule.spotlessClasspath() ++
      SpotlessWorkerModule.worker()
        .prepareOffline(spotlessConfig(), spotlessConfigRoots(), defaultResolver())
  }
}
@mill.api.experimental
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
