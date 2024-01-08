package mill.main

import java.util.concurrent.LinkedBlockingQueue
import coursier.Resolve
import coursier.core.Repository
import mill.T
import mill.define.{Discover, ExternalModule, Target}
import mill.api.{PathRef, Result}
import mill.util.Util.millProjectModule
import os.Path

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
  def classpath: T[Seq[PathRef]] = T {
    millProjectModule("mill-main-graphviz", repositories)
  }

  @deprecated("Use visualizeModuleWorker instead", "Mill 0.11.7")
  def worker = Target.worker {
    val w = visualizeModuleWorker()
    (w.in, w.out)
  }

  def visualizeModuleWorker = T.worker {
    new VisualizeModuleWorker(classpath().map(_.path))
  }
}


