package mill.zinc.worker

import mill.api.daemon.internal.CompileProblemReporter
import mill.javalib.worker.ZincProblem
import sbt.internal.inc.ManagedLoggedReporter


private trait ForwardingReporter(forwarder: CompileProblemReporter) extends ManagedLoggedReporter {
  override def logError(problem: xsbti.Problem): Unit = {
    forwarder.logError(new ZincProblem(problem))
    super.logError(problem)
  }

  override def logWarning(problem: xsbti.Problem): Unit = {
    forwarder.logWarning(new ZincProblem(problem))
    super.logWarning(problem)
  }

  override def logInfo(problem: xsbti.Problem): Unit = {
    forwarder.logInfo(new ZincProblem(problem))
    super.logInfo(problem)
  }

  override def printSummary(): Unit = {
    forwarder.printSummary()
    super.printSummary()
  }
}
