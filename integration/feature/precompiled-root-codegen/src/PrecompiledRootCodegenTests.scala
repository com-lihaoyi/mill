package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object PrecompiledRootCodegenTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("recursiveWildcardFindsPrecompiledYamlModules") - integrationTest { tester =>
      import tester.*

      assert(eval(("resolve", "foo._")).isSuccess)
      assert(eval(("_.compile")).isSuccess)
      assert(eval(("__.compile")).isSuccess)

      val generatedScriptSources = workspacePath / "out/mill-build/generatedScriptSources.dest"
      assert(!os.exists(generatedScriptSources / "wrapped"))
      assert(!os.exists(generatedScriptSources / "support"))
    }
  }
}
