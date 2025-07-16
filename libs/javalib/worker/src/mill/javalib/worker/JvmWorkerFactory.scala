package mill.javalib.worker

import mill.javalib.api.JvmWorkerApi
import mill.javalib.internal.{JvmWorkerArgs, JvmWorkerFactoryApi, ZincCompilerBridge}

//noinspection ScalaUnusedSymbol - used dynamically by classloading via a FQCN
class JvmWorkerFactory extends JvmWorkerFactoryApi {
  override def make(args: JvmWorkerArgs): JvmWorkerApi = JvmWorkerImpl(args)
}
