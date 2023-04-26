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
  override def mimaCheckDirection = CheckDirection.Backward
}


/** Usage

> ./mill prev.publishLocal

> ./mill curr.mimaReportBinaryIssues
...
error: Found 2 issue when checking against org:prev:0.0.1
error:  * static method hello()java.lang.String in class Main does not have a correspondent in current version
error:    filter with: ProblemFilter.exclude[DirectMissingMethodProblem]("Main.hello")
error:  * method hello()java.lang.String in object Main does not have a correspondent in current version
error:    filter with: ProblemFilter.exclude[DirectMissingMethodProblem]("Main.hello")

*/