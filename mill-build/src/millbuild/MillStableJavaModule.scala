package millbuild

import com.github.lolgab.mill.mima.*
import mill.*

/** Publishable module which contains strictly handled API. */
trait MillStableJavaModule extends MillPublishJavaModule with Mima {

  override def mimaBinaryIssueFilters: T[Seq[ProblemFilter]] = Seq.empty[ProblemFilter]

  def mimaPreviousVersions: T[Seq[String]] = Settings.mimaBaseVersions

  def mimaExcludeAnnotations = Seq("mill.api.daemon.experimental")

  override def mimaReportSignatureProblems = true
}
