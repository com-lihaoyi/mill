package mill.javalib.internal

import mill.api.daemon.internal.internal
import mill.javalib.api.internal.JvmWorkerApi

@internal
/**
 * Arguments for the JVM worker construction.
 *
 * @param classPath The classpath of the worker.
 */
case class JvmWorkerArgs[CompilerBridgeData](
    compilerBridge: ZincCompilerBridge[CompilerBridgeData],
    classPath: Seq[os.Path],
    jobs: Int,
    compileToJar: Boolean,
    zincLogDebug: Boolean,
    close0: () => Unit
)

@internal
trait JvmWorkerFactoryApi {
  def make(args: JvmWorkerArgs[Unit]): JvmWorkerApi
}
