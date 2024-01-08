package mill.main

import coursier.Resolve
import coursier.core.Repository
import mill.define.{Discover, ExternalModule, Target, Worker}
import mill.api.{PathRef, Result}
import mill.util.Util.millProjectModule
import os.Path

import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.Await
import scala.concurrent.duration.Duration

object VisualizeModule extends ExternalModule with VisualizeModule {
  def repositories: Seq[Repository] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val repos = Await.result(
      Resolve().finalRepositories.future(),
      Duration.Inf
    )
    repos
  }

  lazy val millDiscover = Discover[this.type]
}

trait VisualizeModule extends mill.define.TaskModule {
  def repositories: Seq[Repository]
  def defaultCommandName() = "run"
  def classpath: Target[Seq[PathRef]] = Target {
    millProjectModule("mill-main-graphviz", repositories).map(_.toSeq)
  }

  @deprecated("Use visualizeModuleWorker instead", "Mill 0.11.7")
  def worker: Worker[(
      LinkedBlockingQueue[(Seq[_], Seq[_], Path)],
      LinkedBlockingQueue[Result[Seq[PathRef]]]
  )] = Target.worker {
    // this worker is no longer functional
    ???
  }

  def visualizeModuleWorker: Worker[VisualizeModuleWorker] = Target.worker {
    new VisualizeModuleWorker(classpath().map(_.path))
  }
}
