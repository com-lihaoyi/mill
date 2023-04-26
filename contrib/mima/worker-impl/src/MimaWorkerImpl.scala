package mill.mima.worker

import mill.mima.worker.api._
import com.typesafe.tools.mima.core.MyProblemReporting
import com.typesafe.tools.mima.core.Problem
import com.typesafe.tools.mima.core.ProblemFilters
import com.typesafe.tools.mima.core.{ProblemFilter => MimaProblemFilter}
import com.typesafe.tools.mima.lib.MiMaLib

import scala.jdk.CollectionConverters._

class MimaWorkerImpl extends MimaWorkerApi {

  def reportBinaryIssues(
      scalaBinaryVersion: String,
      logDebug: java.util.function.Consumer[String],
      logError: java.util.function.Consumer[String],
      logPrintln: java.util.function.Consumer[String],
      checkDirection: CheckDirection,
      runClasspath: Array[java.io.File],
      previous: Array[Artifact],
      current: java.io.File,
      binaryFilters: Array[ProblemFilter],
      backwardFilters: java.util.Map[String, Array[ProblemFilter]],
      forwardFilters: java.util.Map[String, Array[ProblemFilter]],
      excludeAnnos: Array[String],
      publishVersion: String
  ): java.util.Optional[String] = {
    sanityCheckScalaBinaryVersion(scalaBinaryVersion)

    val mimaLib = new MiMaLib(runClasspath.toSeq)

    def isReported(
        versionedFilters: java.util.Map[String, Array[ProblemFilter]]
    )(problem: Problem) = {
      val filters = binaryFilters.map(problemFilterToMima).toSeq
      val mimaVersionedFilters = versionedFilters.asScala.map { case (k, v) =>
        k -> v.map(problemFilterToMima).toSeq
      }.toMap
      MyProblemReporting.isReported(
        publishVersion,
        filters,
        mimaVersionedFilters
      )(problem)
    }

    logPrintln.accept(
      s"Scanning binary compatibility in ${current} ..."
    )
    val (problemsCount, filteredCount) =
      previous.foldLeft((0, 0)) { case ((totalAgg, filteredAgg), prev) =>
        def checkBC =
          mimaLib.collectProblems(prev.file, current, excludeAnnos.toList)

        def checkFC =
          mimaLib.collectProblems(current, prev.file, excludeAnnos.toList)

        val (backward, forward) = checkDirection match {
          case CheckDirection.Backward => (checkBC, Nil)
          case CheckDirection.Forward => (Nil, checkFC)
          case CheckDirection.Both => (checkBC, checkFC)
        }
        val backErrors = backward.filter(isReported(backwardFilters))
        val forwErrors = forward.filter(isReported(forwardFilters))
        val count = backErrors.size + forwErrors.size
        val filteredCount = backward.size + forward.size - count
        val doLog = if (count == 0) logDebug.accept(_) else logError.accept(_)
        doLog(s"Found ${count} issue when checking against ${prev.prettyDep}")
        backErrors.foreach(problem => doLog(prettyProblem("current")(problem)))
        forwErrors.foreach(problem => doLog(prettyProblem("other")(problem)))
        (totalAgg + count, filteredAgg + filteredCount)
      }

    if (problemsCount > 0) {
      val filteredNote =
        if (filteredCount > 0) s" (filtered $filteredCount)" else ""
      java.util.Optional.of(
        s"Failed binary compatibility check! Found $problemsCount potential problems$filteredNote"
      )
    } else {
      logPrintln.accept("Binary compatibility check passed")
      java.util.Optional.empty()
    }
  }

  private def prettyProblem(affected: String)(p: Problem): String = {
    val desc = p.description(affected)
    val howToFilter = p.howToFilter.fold("")(s =>
      s"\n   filter with: ${s.replace("ProblemFilters.exclude", ("ProblemFilter.exclude"))}"
    )
    s" * $desc$howToFilter"
  }

  private def sanityCheckScalaBinaryVersion(scalaBinaryVersion: String) = {
    scalaBinaryVersion match {
      case "2.11" | "2.12" | "2.13" | "3" => // ok
      case _ =>
        throw new IllegalArgumentException(
          s"MiMa supports Scala 2.11, 2.12, 2.13 and 3, not $scalaBinaryVersion"
        )
    }
  }

  private def problemFilterToMima(
      problemFilter: ProblemFilter
  ): MimaProblemFilter =
    ProblemFilters.exclude(problemFilter.problem, problemFilter.name)

}
