package mill.javalib.zinc

import mill.api.daemon.internal.CompileProblemReporter
import sbt.internal.inc.ManagedLoggedReporter

private trait ForwardingReporter(forwarder: CompileProblemReporter) extends ManagedLoggedReporter {
  override def logError(problem: xsbti.Problem): Unit = {
    forwarder.logError(ZincProblem(problem))
    super.logError(problem)
  }

  override def logWarning(problem: xsbti.Problem): Unit = {
    forwarder.logWarning(ZincProblem(problem))
    super.logWarning(problem)
  }

  override def logInfo(problem: xsbti.Problem): Unit = {
    forwarder.logInfo(ZincProblem(problem))
    super.logInfo(problem)
  }

  override def printSummary(): Unit = {
    forwarder.printSummary()
    super.printSummary()
  }
}
