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

    // When the root build is precompiled (so codegen produces no `build_.package_`),
    // the runtime fallback `PrecompiledDiscoveryRootModule` must enumerate every
    // top-level precompiled YAML as a child. Otherwise sibling modules are
    // invisible to `resolve`, cross-sibling `moduleDeps` fail to resolve, and
    // `BuildCtx.rootModule.moduleInternal.modules` only sees the synthetic root.
    test("topLevelResolveListsAllPrecompiledSiblings") - integrationTest { tester =>
      import tester.*

      val result = eval(("resolve", "_"), stdout = os.Pipe)
      assert(result.isSuccess)
      val names = result.out.linesIterator.toSet
      assert(names("foo"))
      assert(names("bar"))
    }

    test("crossSiblingModuleDepsResolveFromYaml") - integrationTest { tester =>
      import tester.*

      // bar declares `moduleDeps: !append [foo]`, so compiling bar requires
      // resolving foo through the discovery root's children.
      assert(eval(("bar.compile")).isSuccess)
    }

    test("buildCtxRootModuleWalkSeesAllSiblings") - integrationTest { tester =>
      import tester.*

      val result = eval(("show", "foo.listModules"), stdout = os.Pipe)
      assert(result.isSuccess)
      val rendered = result.out
      assert(rendered.contains("\"foo\""))
      assert(rendered.contains("\"bar\""))
    }
  }
}
