package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

/**
 * Test that YAML config files can override abstract methods from ScalaModule.
 * This tests the scenario from GitHub issue #6410 where scalaVersion specified
 * in a package.mill.yaml file was not being recognized.
 */
object YamlScalaAbstractTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("subfolder module with scalaVersion in yaml") - integrationTest { tester =>
      // First verify that the scalaVersion is correctly recognized
      val showRes = tester.eval(("show", "foo.scalaVersion"))
      assert(showRes.isSuccess)
      assert(showRes.out.contains("3.7.2"))

      // Now verify compilation works
      val compileRes = tester.eval(("foo.compile"))
      assert(compileRes.isSuccess)

      // And run works
      val runRes = tester.eval(("foo.run"))
      assert(runRes.isSuccess)
    }
  }
}
