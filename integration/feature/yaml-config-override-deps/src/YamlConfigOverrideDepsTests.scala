package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

// Regression test for https://github.com/com-lihaoyi/mill/issues/7083: a task
// whose value is supplied entirely by a non-`!append` YAML override must not
// execute its declared dependencies, since the original task body never runs.
object YamlConfigOverrideDepsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("rootTaskOverride") - integrationTest { tester =>
      val res = tester.eval(("show", "originalTask"))
      assert(res.isSuccess)
      assert(res.out.linesIterator.toSeq.last == "1")
      assert(!res.err.contains("Original task dependency ran"))
      assert(!res.err.contains("original task ran"))
    }
    test("nestedTaskOverride") - integrationTest { tester =>
      val res = tester.eval(("show", "foo.originalTask"))
      assert(res.isSuccess)
      assert(res.out.linesIterator.toSeq.last == "1")
      assert(!res.err.contains("Original task dependency ran"))
      assert(!res.err.contains("original task ran"))
    }
  }
}
