package mill.javalib

import mill.{Task, given}
import mill.api.{Discover, Evaluator, ExternalModule}
import mill.javalib.dependency.{DependencyUpdatesImpl, Format}
import mill.javalib.dependency.updates.ModuleDependenciesUpdates

/**
 * External module providing helper commands related to dependency updates
 */
object Dependency extends ExternalModule {

  /** Calculate possible dependency updates. */
  def updates(
      ev: Evaluator,
      allowPreRelease: Boolean = false
  ): Task.Command[Seq[ModuleDependenciesUpdates]] =
    Task.Command(exclusive = true) {
      if (Task.offline) {
        Task.log.warn("`updates` might not find recent updates in --offline mode")
      }
      DependencyUpdatesImpl(
        ev,
        Task.ctx(),
        ev.rootModule,
        allowPreRelease,
        CoursierConfigModule
      )
    }

  /** Show possible dependency updates. */
  def showUpdates(
      ev: Evaluator,
      allowPreRelease: Boolean = false,
      format: Format = Format.PerModule
  ): Task.Command[Unit] = Task.Command(exclusive = true) {
    DependencyUpdatesImpl.showAllUpdates(updates(ev, allowPreRelease)(), format)
  }

  lazy val millDiscover = Discover[this.type]
}
