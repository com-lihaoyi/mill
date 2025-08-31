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
    // refactored layout modules for extensibility
    ProblemFilter.exclude[NewMixinForwarderProblem]("mill.javalib.MavenModule.sources"),
    ProblemFilter.exclude[NewMixinForwarderProblem]("mill.javalib.MavenModule#MavenTests.sources"),
    ProblemFilter.exclude[NewMixinForwarderProblem]("mill.scalalib.SbtModule.sources"),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.SbtModule.mill$scalalib$SbtModule$$super$sourcesFolders"
    ),
    ProblemFilter.exclude[NewMixinForwarderProblem]("mill.scalalib.SbtModule#SbtTests.sources"),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.scalalib.SbtModule#SbtTests.mill$scalalib$SbtModule$SbtTests$$super$sourcesFolders"
    )
  )

  def mimaPreviousVersions: T[Seq[String]] = Settings.mimaBaseVersions

  def mimaExcludeAnnotations = Seq("mill.api.daemon.experimental")

  override def mimaReportSignatureProblems = true
}
