package millbuild
import mill.*
import mill.javalib.PublishModule
import mill.javalib.publish.*
import mill.scalajslib.ScalaJSModule
import mill.scalajslib.api.*
import mill.scalalib.*
import mill.scalanativelib.ScalaNativeModule
import mill.scalanativelib.api.*
trait ProjectBaseModule extends PublishModule, SbtPlatformModule {

  def mvnDeps = Seq(Deps.upickle)

  def scalaVersion = "2.13.14"

  def publishVersion = "0.1.0-SNAPSHOT"

  trait Tests extends SbtPlatformTests {

    def mvnDeps = Seq(Deps.utest)

    def testParallelism = false

    def testSandboxWorkingDir = false

  }
}
