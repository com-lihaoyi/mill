package mill.javalib.worker

import mill.api.daemon.internal.internal
import sbt.internal.inc.Analysis
import xsbti.compile.CompileAnalysis

import scala.collection.mutable

/**
 * A zinc reporter which records reported problems
 * and is able to log all not already reported problems later
 * via [[logOldProblems(CompileAnalysis)]].
 */
@internal
trait RecordingReporter extends xsbti.Reporter {
  // `lazy` to allow initialization before first access
  private lazy val seenProblems: mutable.Set[xsbti.Problem] = mutable.Set()

  abstract override def log(problem: xsbti.Problem): Unit = {
    super.log(problem)
    seenProblems.add(problem)
  }

  abstract override def reset(): Unit = {
    super.reset()
    seenProblems.clear()
  }

  // we need to override this, to allow the call from [[printOldProblems]]
  abstract override def printSummary(): Unit = super.printSummary()

  /**
   * Log problem contained in the given [[CompileAnalysis]]
   * but not already reported.
   */
  def logOldProblems(compileAnalysis: CompileAnalysis): Unit = {
    val problems = compileAnalysis match {
      // Looks like the info we need is only contained in Analysis
      case a: Analysis =>
        a.infos.allInfos.values.flatMap(i =>
          i.getReportedProblems ++ i.getUnreportedProblems
        )
      case _ => Nil
    }
    val oldProblems = problems.filterNot(seenProblems)
    if (oldProblems.nonEmpty) {
      oldProblems.foreach(log)
      // show a summary again
      super.printSummary()
    }
  }
}
