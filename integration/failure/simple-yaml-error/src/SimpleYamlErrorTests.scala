package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object SimpleYamlErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("foo.scalaVersion")

      assert(!res.isSuccess)

      assert(res.err.contains(
        """object creation impossible, since def scalaVersion: mill.api.Task.Simple[String] in trait ScalaModule in package mill.scalalib is not defined"""
      ))
      assert(res.err.contains("""method scalaVersionWrong overrides nothing"""))

    }
  }
}
