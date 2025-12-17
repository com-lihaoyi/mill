package mill.javalib.api

import mill.javalib.api.internal.ZincCompilerBridgeProvider
import mill.api.daemon.internal.internal
import mill.javalib.api.internal.InternalJvmWorkerApi

@internal
/**
 * Arguments for the JVM worker construction.
 *
 * @param classPath The classpath of the worker.
 */
case class JvmWorkerArgs(
    compilerBridge: ZincCompilerBridgeProvider,
    classPath: Seq[os.Path],
    jobs: Int,
    zincLogDebug: Boolean,
    close0: () => Unit
)

@internal
trait JvmWorkerFactoryApi {
  def make(args: JvmWorkerArgs): InternalJvmWorkerApi
}
