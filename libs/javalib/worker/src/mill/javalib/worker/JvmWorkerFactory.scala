package mill.javalib.worker

import mill.javalib.api.internal.JvmWorkerApi
import mill.javalib.internal.{JvmWorkerArgs, JvmWorkerFactoryApi}

//noinspection ScalaUnusedSymbol - used dynamically by classloading via a FQCN
class JvmWorkerFactory extends JvmWorkerFactoryApi {
  override def make(args: JvmWorkerArgs): JvmWorkerApi = JvmWorkerImpl(args)
}
