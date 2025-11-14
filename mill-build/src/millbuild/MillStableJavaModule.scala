package millbuild

import com.github.lolgab.mill.mima.*
import mill.*

/** Publishable module which contains strictly handled API. */
trait MillStableJavaModule extends MillPublishJavaModule with Mima {

  override def mimaBinaryIssueFilters: T[Seq[ProblemFilter]] = Seq(
    // private class
    ProblemFilter.exclude[Problem]("mill.javalib.RunModule#RunnerImpl*"),
    // forgot to mark this class experimental
    ProblemFilter.exclude[MissingClassProblem]("mill.kotlinlib.ksp.GeneratedKSPSources"),
    ProblemFilter.exclude[MissingClassProblem]("mill.kotlinlib.ksp.GeneratedKSPSources$"),
    // private class
    ProblemFilter.exclude[Problem]("mill.api.internal.Resolved*"),
    ProblemFilter.exclude[Problem]("mill.util.RequestId*"),
    ProblemFilter.exclude[Problem]("mill.util.Timed*"),
    // internal stuff
    ProblemFilter.exclude[Problem]("mill.javalib.api.internal.*"),
    ProblemFilter.exclude[Problem]("mill.javalib.internal.*")
  )

  def mimaPreviousVersions: T[Seq[String]] = Settings.mimaBaseVersions

  def mimaExcludeAnnotations = Seq("mill.api.daemon.experimental")

  override def mimaReportSignatureProblems = true
}
