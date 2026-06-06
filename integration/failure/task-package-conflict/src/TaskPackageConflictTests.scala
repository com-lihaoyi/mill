package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object TestPackageConflictTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("foo")
      assert(!res.isSuccess)
      // TODO: re-enable once upstream fix lands: https://github.com/scala/scala3/pull/25706
      // Symbol.sourcePos doesn't apply -Ymagic-offset-header remapping, so
      // DoubleDefinition errors still show the generated wrapper path.
      // assert(!res.err.contains("generatedScriptSources.dest"))
      // assert(res.err.contains("[error] build.mill:"))
    }
  }
}
