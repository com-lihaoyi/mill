package mill.scalajslib

import mill._
import mill.define.Discover
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.scalalib.{DepSyntax, PublishModule, ScalaModule, TestModule}
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object LegacyOptTests extends TestSuite {
  import CompileLinkTests._

  def tests: Tests = Tests {

    test("fullOpt") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = true)
      )
    }
    test("fastOpt") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = true)
      )
    }
  }

}
