package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object TestClassloaderShutdownTests extends UtestIntegrationTestSuite {
  private val closedEarly = "TEST_CLASSLOADER_CLOSED_EARLY"
  private val catsEffectFailure = "NoClassDefFoundError: cats/effect/unsafe/UnsafeNonFatal"
  private val setup = "LIFECYCLE_PROBE_SETUP="

  val tests: Tests = Tests {
    test("frameworkThreads") - integrationTest { tester =>
      val result = tester.eval("probe.test")
      assert(result.isSuccess)
      assert(!result.err.contains(closedEarly))
      assert(result.err.linesIterator.filter(_.startsWith(setup)).toSeq == Seq(
        s"$setup${tester.workspacePath}"
      ))
      assert(tester.eval("version").isSuccess)
    }

    test("weaverCatsEffect") - integrationTest { tester =>
      for (_ <- 1 to 5) {
        val result = tester.eval("weaver.test")
        assert(result.isSuccess)
        assert(!result.err.contains(catsEffectFailure))
      }
    }

    test("unrelatedFramework") - integrationTest { tester =>
      val result = tester.eval("unrelated.test")
      assert(result.isSuccess)
      assert(result.out.contains("Tests: 1, Passed: 1, Failed: 0"))
    }
  }
}
