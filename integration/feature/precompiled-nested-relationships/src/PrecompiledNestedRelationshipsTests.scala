package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

/**
 * Exercises the matrix of parent/child relationships across compiled and
 * precompiled module boundaries that wasn't covered elsewhere:
 *
 *   - `compiledChild.precompiledLeaf`: precompiled grandchild whose parent is
 *     a compiled `package.mill`. The codegen-emitted `precompiledChildNames`
 *     path wires it as a Scala alias inside `compiledChild.package_`.
 *   - `precompiledChild → compiledChild` cross-shape `moduleDeps`: a
 *     precompiled YAML referencing a compiled sibling, resolved through
 *     codegen's `PrecompiledModuleRef.resolveModuleRef` walk on the enclosing
 *     `build_.package_`.
 *
 * Depth-2 *orphan* precompileds (those with neither a compiled nor a
 * codegen-emitted ancestor between them and root) are intentionally left
 * untested here: `ResolveCore` materializes `DynamicModule` children using
 * only `child.moduleSegments.last.value`, so a depth-2 child's segment
 * prefix would be dropped on the resolve side. Depth-1 orphans are exercised
 * by `precompiled-root-codegen`.
 */
object PrecompiledNestedRelationshipsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("recursiveResolveListsAllShapes") - integrationTest { tester =>
      import tester.*

      val result = eval(("resolve", "__"), stdout = os.Pipe)
      assert(result.isSuccess)
      val names = result.out.linesIterator.toSet
      assert(names("compiledChild"))
      assert(names("compiledChild.precompiledLeaf"))
      assert(names("precompiledChild"))
    }

    // Codegen-alias path at depth-2: `precompiledLeaf` is a child of a compiled
    // `package.mill`, so it appears as a Scala lazy val on `compiledChild.package_`.
    test("compiledChildPrecompiledLeafCompiles") - integrationTest { tester =>
      assert(tester.eval(("compiledChild.precompiledLeaf.compile")).isSuccess)
    }

    // Cross-shape sibling dep: `precompiledChild`'s YAML moduleDeps references
    // `compiledChild` (a codegen-emitted child of `build_.package_`). The
    // codegen-emitted `PrecompiledModuleRef.resolveModuleRef(this, "compiledChild", ...)`
    // resolves via JVM method reflection on the enclosing `package_`.
    test("precompiledChildClasspathContainsCompiledSibling") - integrationTest { tester =>
      import tester.*

      val result = eval(("show", "precompiledChild.compileClasspath"), stdout = os.Pipe)
      assert(result.isSuccess)
      assert(result.out.contains("/compiledChild/compile.dest/classes"))
    }
  }
}
