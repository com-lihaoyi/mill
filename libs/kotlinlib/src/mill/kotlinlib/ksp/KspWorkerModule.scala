package mill.kotlinlib.ksp

import mill.api.{Discover, ExternalModule}
import mill.kotlinlib.ksp2.{KspWorker, KspWorkerArgs}

@mill.api.experimental
trait KspWorkerModule extends mill.Module {
  def runKsp(
      kspWorkerArgs: KspWorkerArgs,
      kspWorker: KspWorker,
      symbolProcessorClassloader: ClassLoader,
      kspArgs: Seq[String]
  ): Unit = {

    kspWorker.runKsp(
      symbolProcessorClassloader,
      kspWorkerArgs,
      kspArgs
    )
  }
}

@mill.api.experimental
object KspWorkerModule extends ExternalModule with KspWorkerModule {
  override def millDiscover: Discover = Discover[this.type]
}
