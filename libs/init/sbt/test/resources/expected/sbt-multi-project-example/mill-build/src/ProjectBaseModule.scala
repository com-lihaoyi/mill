package millbuild
import mill.*
import mill.javalib.PublishModule
import mill.javalib.publish.*
import mill.scalalib.*
trait ProjectBaseModule extends PublishModule, SbtModule {

  def scalaVersion = "2.12.3"

  def scalacOptions = Seq(
    "-unchecked",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:postfixOps",
    "-deprecation"
  )

  def scalacPluginMvnDeps = Seq(Deps.wartremover)

  def publishVersion = "0.1.0-SNAPSHOT"

  def repositories = Seq(
    "https://oss.sonatype.org/service/local/repositories/releases/content/",
    "https://oss.sonatype.org/content/repositories/snapshots"
  )

  trait Tests extends SbtTests, TestModule.ScalaTest {

    def mvnDeps = Seq(Deps.scalatest, Deps.scalacheck)

    def testParallelism = false

    def testSandboxWorkingDir = false

  }
}
