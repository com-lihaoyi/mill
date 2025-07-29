package mill.scalalib

import mill.*
import mill.testkit.TestRootModule
import utest.*
import HelloWorldTests.*
import mill.api.Discover

object ScalaCrossVersionTests extends TestSuite {

  object CrossModuleDeps extends TestRootModule {
    object stable extends Cross[Stable](scala212Version, scala32Version)
    trait Stable extends CrossScalaModule

    object cuttingEdge extends Cross[CuttingEdge](scala213Version, scala33Version)
    trait CuttingEdge extends CrossScalaModule {
      def moduleDeps = Seq(stable())
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("scala-33-depend-on-scala-32-works") {
      CrossModuleDeps.cuttingEdge(scala33Version).moduleDeps
    }
    test("scala-213-depend-on-scala-212-fails") {
      val message = assertThrows[Exception](
        CrossModuleDeps.cuttingEdge(scala213Version).moduleDeps
      ).getMessage
      assert(
        message == s"Unable to find compatible cross version between ${scala213Version} and 2.12.6,3.2.0"
      )
    }
  }
}
