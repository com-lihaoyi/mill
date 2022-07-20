package mill.playlib

import mill.{Module, T}
import mill.define.{Discover, ExternalModule, Worker}

trait RouteCompilerWorkerModule extends Module {
  def routeCompilerWorker: Worker[RouteCompilerWorker] = T.worker {
    new RouteCompilerWorker()
  }
}

private[playlib] object RouteCompilerWorkerModule
    extends ExternalModule
    with RouteCompilerWorkerModule {
  lazy val millDiscover = Discover[this.type]
}
