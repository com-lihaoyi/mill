package mill.api.daemon.internal.bsp

import mill.api.daemon.internal.EvaluatorApi
import mill.api.daemon.Watchable

trait BspBootstrapBridge {

  def runBootstrap[T](
      activeCommandMessage: String,
      body: BspBootstrapBridge.Body[T]
  ): T
}

object BspBootstrapBridge {
  final case class BootstrapState(
      evaluators: java.util.List[EvaluatorApi],
      watched: java.util.List[Watchable],
      errorOpt: Option[String]
  )

  @FunctionalInterface
  trait Body[T] {
    def apply(state: BootstrapState): T
  }
}
