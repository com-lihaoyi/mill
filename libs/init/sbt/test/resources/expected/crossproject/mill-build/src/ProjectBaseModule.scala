package millbuild

import mill.*
import mill.javalib.PublishModule
import mill.javalib.publish.*
import mill.scalajslib.ScalaJSModule
import mill.scalajslib.api.*
import mill.scalalib.*
import mill.scalanativelib.ScalaNativeModule
import mill.scalanativelib.api.*

trait ProjectBaseModule extends SbtPlatformModule, PublishModule {

  def mvnDeps = Seq(Deps.upickle)

  def scalaVersion = "2.13.18"

  def publishVersion = "0.1.0-SNAPSHOT"

  trait ProjectBaseTests extends SbtPlatformTests, TestModule.Utest {

    def mvnDeps = super.mvnDeps() ++ Seq(Deps.utest)

    def testParallelism = false

    def testSandboxWorkingDir = false

  }

}
