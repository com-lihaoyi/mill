import mill.Cross
import mill.scalalib.{SbtModule, PublishModule, Dep, CrossSbtModule, DepSyntax}
import mill.scalalib.publish.{PomSettings, License, Developer, VersionControl}

object acyclic extends Cross[AcyclicModule]("2.10.6", "2.11.8", "2.12.3", "2.12.5")
class AcyclicModule(val crossScalaVersion: String) extends CrossSbtModule with PublishModule {
  def millSourcePath = super.millSourcePath / os.up
  def artifactName = "acyclic"
  def publishVersion = "0.1.7"

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/acyclic",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "acyclic"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi")
    )
  )

  def ivyDeps = Agg(
    ivy"${scalaOrganization()}:scala-compiler:${scalaVersion()}"
  )
  object test extends Tests{
    def forkWorkingDir = os.pwd / "target" / "workspace" / "acyclic"
    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest:0.6.0"
    )
    def testFrameworks = Seq("utest.runner.Framework")
  }
}
