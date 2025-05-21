package mill.scalalib

import mill.{Task, given}
import mill.define.{Discover, Evaluator, ExternalModule}
import mill.scalalib.dependency.{DependencyUpdatesImpl, Format}
import mill.scalalib.dependency.updates.ModuleDependenciesUpdates

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
        ev.rootModule.moduleCtx.discover,
        allowPreRelease
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
