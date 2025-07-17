package mill.javalib.internal

import mill.api.daemon.internal.internal
import mill.javalib.api.JvmWorkerApi

@internal
/** Arguments for the JVM worker construction. */
case class JvmWorkerArgs(
  compilerBridge: ZincCompilerBridge, jobs: Int, compileToJar: Boolean, zincLogDebug: Boolean, close0: () => Unit
)

@internal
trait JvmWorkerFactoryApi {
  def make(args: JvmWorkerArgs): JvmWorkerApi
}
