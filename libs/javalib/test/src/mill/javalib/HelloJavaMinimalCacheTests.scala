package mill.javalib

import mill.*
import mill.api.{Discover, Task}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import utest.framework.TestPath

/**
 * Reproduce cache-miss when a cache value for a PathRef-result should be present.
 * This is an issue with out pathref-mangling to replace known root variables
 */
object HelloJavaMinimalCacheTests extends TestSuite {

  object HelloJava extends TestRootModule {
    object core extends JavaModule
    protected lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR").trim()) / "hello-java"

  def testEval() = UnitTester(HelloJava, resourcePath, debugEnabled = true)
  def tests: Tests = Tests {
    test("javacOptions") {
      println("Test: " + summon[TestPath].value.mkString("."))
      testEval().scoped { eval =>

        val Right(result1) = eval.apply(HelloJava.core.compileResources): @unchecked
        val Right(result2) = eval.apply(HelloJava.core.compileResources): @unchecked
        val Right(result3) = eval.apply(HelloJava.core.compileResources): @unchecked

        assert(
          result1.value == result2.value,
          result1.evalCount != 0,
          result2.evalCount == 0,
          result3.evalCount == 0,
        )
      }
    }
  }
}
