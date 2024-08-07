package mill.scalalib

import mill.{Task, given}
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.scalalib.dependency.{DependencyUpdatesImpl, Format}
import mill.scalalib.dependency.updates.ModuleDependenciesUpdates

object Dependency extends ExternalModule {

  /** Calculate possible dependency updates. */
  def updates(
      ev: Evaluator,
      allowPreRelease: Boolean = false
  ): Command[Seq[ModuleDependenciesUpdates]] =
    Task.Command {
      DependencyUpdatesImpl(
        ev,
        ???,// implicitly,
        ev.rootModule,
        ev.rootModule.millDiscover,
        allowPreRelease
      )
    }

  /** Show possible dependency updates. */
  def showUpdates(
      ev: Evaluator,
      allowPreRelease: Boolean = false,
      format: Format = Format.PerModule
  ): Command[Unit] = Task.Command {
    DependencyUpdatesImpl.showAllUpdates(updates(ev, allowPreRelease)(), format)
  }

  @deprecated("Use other overload instead", "Mill after 0.11.6")
  def showUpdates(ev: Evaluator, allowPreRelease: Boolean): Command[Unit] =
    Dependency.showUpdates(ev, allowPreRelease, Format.PerModule)

  lazy val millDiscover: Discover = Discover[this.type]
}
