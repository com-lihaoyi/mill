import mill.Cross
import mill.scalalib.{SbtModule, PublishModule, Dep, CrossSbtModule}
import mill.scalalib.publish.{PomSettings, License, Developer, SCM}

object acyclic extends Cross[AcyclicModule]("2.10.6", "2.11.8", "2.12.3", "2.12.4")
class AcyclicModule(val crossScalaVersion: String) extends CrossSbtModule with PublishModule {
  def basePath = super.basePath / ammonite.ops.up
  def artifactName = "acyclic"
  def publishVersion = "0.1.7"

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/acyclic",
    licenses = Seq(
      License("MIT license", "http://www.opensource.org/licenses/mit-license.php")
    ),
    scm = SCM(
      "git://github.com/lihaoyi/acyclic.git",
      "scm:git://github.com/lihaoyi/acyclic.git"
    ),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi")
    )
  )

  def ivyDeps = Agg(
    Dep.Java("org.scala-lang", "scala-compiler", scalaVersion())
  )
  object test extends Tests{
    def forkWorkingDir = ammonite.ops.pwd / 'target / 'workspace / 'acyclic
    def ivyDeps = Agg(
      Dep("com.lihaoyi", "utest", "0.6.0")
    )
    def testFramework = "utest.runner.Framework"
  }
}