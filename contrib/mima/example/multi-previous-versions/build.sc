import mill._

import mill.scalalib._
import mill.scalalib.publish._
import mill.mima._
import coursier.ivy.IvyRepository
import mill.define.Target
import os.Path

trait Common extends ScalaModule with PublishModule {
  def scalaVersion = "2.13.4"
  def pomSettings =
    PomSettings("", organization = "org", "", Seq(), VersionControl(), Seq())
}
object prev extends Common {
  def publishVersion = "0.0.1"
}
object prev2 extends Common {
  override def millSourcePath = curr.millSourcePath
  override def artifactName = "prev"
  override def publishVersion = "0.0.2"
}

object curr extends Common with Mima {
  def publishVersion = "0.0.3"
  def mimaPreviousArtifacts = T(
    Agg(ivy"org::prev:0.0.1", ivy"org::prev:0.0.2")
  )
}


def mimaPreviousArtifactsSize = T{ curr.mimaPreviousArtifacts().iterator.size }

/** Usage

> ./mill prev.publishLocal
> ./mill prev2.publishLocal

> ./mill show mimaPreviousArtifactsSize
2

> ./mill curr.mimaReportBinaryIssues
...
error: Found 2 issue when checking against org:prev:0.0.1
error:  * static method hello()java.lang.String in class Main does not have a correspondent in current version
error:    filter with: ProblemFilter.exclude[DirectMissingMethodProblem]("Main.hello")
error:  * method hello()java.lang.String in object Main does not have a correspondent in current version
error:    filter with: ProblemFilter.exclude[DirectMissingMethodProblem]("Main.hello")

*/