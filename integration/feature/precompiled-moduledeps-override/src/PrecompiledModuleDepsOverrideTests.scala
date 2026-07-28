package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

/**
 * Exercises the pattern where a precompiled `JavaModule` subclass programmatically
 * overrides `def moduleDeps` by walking `BuildCtx.rootModule.moduleInternal.modules`.
 * Both modules share the same `SimpleJavaModule` class — the per-instance
 * relationship is driven entirely by `moduleSegments.last.value` matching.
 *
 * Asserts both the reported shape (via a `listModuleDeps` task that just prints
 * the override result) and that the deps actually flow through to compile-graph
 * resolution.
 */
object PrecompiledModuleDepsOverrideTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("listModuleDepsReturnsExpectedShape") - integrationTest { tester =>
      import tester.*

      val result = eval(("show", "__.listModuleDeps"), stdout = os.Pipe)
      assert(result.isSuccess)
      val normalized = result.out.replaceAll("\\s+", "")
      assert(normalized.contains("\"foo.listModuleDeps\":[\"bar\"]"))
      assert(normalized.contains("\"bar.listModuleDeps\":[]"))
    }

    test("overrideFlowsThroughCompileClasspath") - integrationTest { tester =>
      import tester.*

      val fooCp = eval(("show", "foo.compileClasspath"), stdout = os.Pipe)
      assert(fooCp.isSuccess)
      assert(fooCp.out.contains("/bar/compile.dest/classes"))

      val barCp = eval(("show", "bar.compileClasspath"), stdout = os.Pipe)
      assert(barCp.isSuccess)
      assert(!barCp.out.contains("/foo/compile.dest/classes"))
    }
  }
}
