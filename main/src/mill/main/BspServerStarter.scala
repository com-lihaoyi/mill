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
