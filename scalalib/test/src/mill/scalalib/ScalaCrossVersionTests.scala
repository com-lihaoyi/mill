package mill.scalalib

import mill._
import mill.testkit.TestBaseModule
import utest._

import HelloWorldTests._

object ScalaCrossVersionTests extends TestSuite {

  object CrossModuleDeps extends TestBaseModule {
    object stable extends Cross[Stable](scala212Version, scala32Version)
    trait Stable extends CrossScalaModule

    object cuttingEdge extends Cross[CuttingEdge](scala213Version, scala33Version)
    trait CuttingEdge extends CrossScalaModule {
      def moduleDeps = Seq(stable())
    }
  }

  def tests: Tests = Tests {

    test("scala-33-depend-on-scala-32-works") {
      CrossModuleDeps.cuttingEdge(scala33Version).moduleDeps
    }
    test("scala-213-depend-on-scala-212-fails") {
      val message = intercept[Exception](
        CrossModuleDeps.cuttingEdge(scala213Version).moduleDeps
      ).getMessage
      assert(
        message == s"Unable to find compatible cross version between ${scala213Version} and 2.12.6,3.2.0"
      )
    }
  }
}
