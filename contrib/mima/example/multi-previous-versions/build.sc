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
    Agg(
      ivy"org::prev:0.0.1",
      ivy"org::prev:0.0.2"
    )
  )
}

val repo = sys.props("ivy.home") + "/local"

def prepare() = T.command {
  prev.publishLocal(repo)()
  prev2.publishLocal(repo)()
  ()
}

def verify() = T.command {
  assert(curr.mimaPreviousArtifacts().iterator.size == 2)
  ()
}

def verifyFail() = T.command {
  curr.mimaReportBinaryIssues()()
}


/** Usage

> ./mill prepare

> ./mill verify

> ./mill verifyFail
error:

 */