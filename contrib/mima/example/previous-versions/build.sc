
import $ivy.`org.scalameta::munit:0.7.27`

import mill.mima._
import mill._
import mill.scalalib._
import mill.scalalib.publish._
import mill.scalajslib.ScalaJSModule
import munit.Assertions._

trait Common extends ScalaModule with PublishModule with Mima { outer =>
  def scalaVersion = "2.13.6"
  def publishVersion = "0.0.1"
  override def artifactName = "prev"
  def pomSettings =
    PomSettings("", organization = "org", "", Seq(), VersionControl(), Seq())
  trait Js extends Common with ScalaJSModule {
    override def millSourcePath = outer.millSourcePath
    override def scalaJSVersion = "1.6.0"
    override def artifactName = "prev-js"
    override def mimaPreviousVersions = outer.mimaPreviousVersions
  }
}
object prev extends Common {
  object js extends Js
}
object curr extends Common with Mima {
  override def mimaPreviousVersions = T(Seq("0.0.1"))
  object js extends Js
}

def prepare() = T.command {
  prev.publishLocal(sys.props("ivy.home") + "/local")()
  prev.js.publishLocal(sys.props("ivy.home") + "/local")()
}

def verify() = T.command {
  // tests mimaPreviousVersions
  assertEquals(curr.mimaPreviousArtifacts(), Agg(ivy"org:prev_2.13:0.0.1"))
  assertEquals(
    curr.js.mimaPreviousArtifacts(),
    Agg(ivy"org:prev-js_sjs1_2.13:0.0.1")
  )
}

def verifyFail() = T.command {
  curr.mimaReportBinaryIssues()()
}

def verifyFailJs() = T.command {
  curr.js.mimaReportBinaryIssues()()
}


/** Usage

> ./mill prepare

> ./mill verify

> ./mill verifyFail
error:

> ./mill verifyFailJs
error:

*/