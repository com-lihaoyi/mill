
import mill._
import mill.scalalib._
import mill.scalalib.publish._
import mill.mima._

trait Common extends ScalaModule with PublishModule {
  def scalaVersion = "2.13.4"
  def publishVersion = "0.0.1"
  def pomSettings =
    PomSettings("", organization = "org", "", Seq(), VersionControl(), Seq())
}
object prev extends Common
object curr extends Common with Mima {
  override def mimaPreviousArtifacts = T(Agg(ivy"org::prev:0.0.1"))
  override def mimaBinaryIssueFilters = T {
    Seq(ProblemFilter.exclude[Problem]("foo.Foo.*"))
  }
}

/** Usage

> ./mill prev.publishLocal

> ./mill curr.mimaReportBinaryIssues
...
Binary compatibility check passed

*/