package millbuild
import mill.*
import mill.api.opt.*
import mill.javalib.PublishModule
import mill.javalib.publish.*
import mill.scalajslib.ScalaJSModule
import mill.scalajslib.api.*
import mill.scalalib.*
import mill.scalanativelib.ScalaNativeModule
import mill.scalanativelib.api.*
trait ProjectBaseModule extends PublishModule, CrossSbtPlatformModule {

  def mvnDeps = Seq(Deps.upickle)

  def scalacOptions = Opts("-deprecation") ++
    (crossScalaVersion match {
      case "2.12.20" => Opts(
          "-Xlint:_,-unused",
          "-Ywarn-numeric-widen",
          "-Ywarn-unused:_,-nowarn,-privates"
        )
      case "2.13.14" => Opts("-Xlint:_,-unused", "-Wnumeric-widen", "-Wunused")
      case "3.7.1"   => Opts("-Wunused")
      case _         => Opts()
    })

  def publishVersion = "0.1.0-SNAPSHOT"

  trait Tests extends CrossSbtPlatformTests {

    def testParallelism = false

    def testSandboxWorkingDir = false

    def testFramework = sys.error("no test framework")

  }
}
