package millbuild

import com.github.lolgab.mill.mima.*
import mill.*

/** Publishable module which contains strictly handled API. */
trait MillStableJavaModule extends MillPublishJavaModule with Mima {

  override def mimaBinaryIssueFilters: T[Seq[ProblemFilter]] = Seq(
    // never called directly, so doesn't matter if it exists or not
    ProblemFilter.exclude[Problem]("*<clinit>"),
    // private class
    ProblemFilter.exclude[Problem]("mill.javalib.RunModule#RunnerImpl*"),
    // forgot to mark this class experimental
    ProblemFilter.exclude[MissingClassProblem]("mill.kotlinlib.ksp.GeneratedKSPSources"),
    ProblemFilter.exclude[MissingClassProblem]("mill.kotlinlib.ksp.GeneratedKSPSources$"),
    // private class
    ProblemFilter.exclude[Problem]("mill.api.internal.Resolved*"),
    ProblemFilter.exclude[Problem]("mill.util.RequestId*"),
    ProblemFilter.exclude[Problem]("mill.util.Timed*"),
    ProblemFilter.exclude[Problem]("mill.javalib.bsp.BspRunModule*"),
    // internal stuff
    ProblemFilter.exclude[Problem]("mill.javalib.api.internal.*"),
    ProblemFilter.exclude[Problem]("mill.javalib.internal.*"),
    ProblemFilter.exclude[Problem]("mill.api.daemon.internal.MillScalaParser.*"),
    // Replaced static-forwarder (to the same method in companion objects) by non-static method
    // This is a real breakage, but probably one that won't hurt any Mill user
    ProblemFilter.exclude[StaticVirtualMemberProblem]("mill.javalib.AssemblyModule.prepareOffline"),
    ProblemFilter.exclude[ReversedMissingMethodProblem](
      "mill.javalib.AssemblyModule.mill$javalib$AssemblyModule$$super$prepareOffline"
    ),
    // This API was broken because we leaked internal dependencies
    // see https://github.com/com-lihaoyi/mill/issues/6251
    ProblemFilter.exclude[IncompatibleMethTypeProblem](
      "mill.javalib.SonatypeCentralPublisher.this"
    ),
    ProblemFilter.exclude[IncompatibleMethTypeProblem](
      "mill.javalib.SonatypeCentralPublisher.publish"
    ),
    ProblemFilter.exclude[IncompatibleMethTypeProblem](
      "mill.javalib.SonatypeCentralPublisher.publishAll"
    ),
    ProblemFilter.exclude[IncompatibleMethTypeProblem](
      "mill.javalib.SonatypeCentralPublisher.publishAllByMap"
    ),
    // private method
    ProblemFilter.exclude[Problem](
      "mill.api.daemon.internal.ExecutionResultsApi.formatFailing"
    )
  )

  def mimaPreviousVersions: T[Seq[String]] = Settings.mimaBaseVersions

  def mimaExcludeAnnotations = Seq("mill.api.daemon.experimental")

  override def mimaReportSignatureProblems = true
}
