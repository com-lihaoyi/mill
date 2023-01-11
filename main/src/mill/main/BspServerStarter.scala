package mill.main

import mill.eval.Evaluator

import java.io.{InputStream, PrintStream}
import scala.concurrent.Promise

trait BspServerStarter {
  def startBspServer(
      initialEvaluator: Option[Evaluator],
      outStream: PrintStream,
      errStream: PrintStream,
      inStream: InputStream,
      workspaceDir: os.Path,
      ammoniteHomeDir: os.Path,
      canReload: Boolean,
      serverHandle: Option[Promise[BspServerHandle]] = None
  ): BspServerResult
}

object BspServerStarter {
  def apply(): BspServerStarter = {
    // We cannot link this class directly, as it would give us a circular dependency
    val bspClass = getClass().getClassLoader.loadClass("mill.bsp.BspServerStarterImpl")
    val method = bspClass.getMethod("get")
    method.invoke(null).asInstanceOf[BspServerStarter]
  }
}
