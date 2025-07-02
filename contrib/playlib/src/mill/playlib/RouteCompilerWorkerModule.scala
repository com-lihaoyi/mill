package mill.playlib

import mill.{Module, Task, Worker}
import mill.api.{Discover, ExternalModule}

trait RouteCompilerWorkerModule extends Module {
  def routeCompilerWorker: Worker[RouteCompilerWorker] = Task.Worker {
    new RouteCompilerWorker()
  }
}

private[playlib] object RouteCompilerWorkerModule
    extends ExternalModule
    with RouteCompilerWorkerModule {
  lazy val millDiscover = Discover[this.type]
}
