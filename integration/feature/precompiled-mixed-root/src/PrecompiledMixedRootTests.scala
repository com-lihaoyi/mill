package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

/**
 * Exercises a workspace that mixes a compiled root `build.mill` with a
 * top-level precompiled YAML sibling. With the additive `MainRootModule`
 * children override, both shapes coexist:
 *
 *   - `compiledChild` shows up via the codegen-emitted Scala alias.
 *   - `foo` (precompiled) shows up via the same codegen alias mechanism today
 *     because it lives directly under a compiled parent — but `resolve _` and
 *     `PrecompiledModule.all` must list both regardless.
 *
 * This locks in the contract that the augmented `MainRootModule` does not
 * regress the codegen-alias path for mixed roots.
 */
object PrecompiledMixedRootTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("resolveListsBothCompiledAndPrecompiledChildren") - integrationTest { tester =>
      import tester.*

      val result = eval(("resolve", "_"), stdout = os.Pipe)
      assert(result.isSuccess)
      val names = result.out.linesIterator.toSet
      assert(names("compiledChild"))
      assert(names("foo"))
    }

    test("compiledRootTasksStillWork") - integrationTest { tester =>
      assert(tester.eval(("compiledChild.compile")).isSuccess)
    }

    test("precompiledChildTasksWork") - integrationTest { tester =>
      assert(tester.eval(("foo.compile")).isSuccess)
    }
  }
}
