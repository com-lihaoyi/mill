package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

/** Makes sure that mill client launcher does not load scala classes. */
object NoScalaInLauncherTests extends UtestIntegrationTestSuite {
  def doTest(bsp: Boolean): Unit = integrationTest { tester =>
    import tester.proc

    val preppedEval = proc(
      if (bsp) "--bsp" else "doesNotMatter",
      env = Map("JAVA_OPTS" -> "-verbose:class", "MILL_TEST_EXIT_AFTER_BSP_CHECK" -> "1")
    )

    withTestClues(preppedEval.clues*) {
      val res = preppedEval.run()
      assert(res.isSuccess)
      val scalaClasses = res.out.linesIterator.filter(_.contains("scala.")).toVector

      if (bsp) assert(scalaClasses.nonEmpty)
      else assert(scalaClasses.isEmpty)
    }
  }

  val tests: Tests = Tests {
    test("noBsp") - doTest(bsp = false)
    test("bsp") - doTest(bsp = true)
  }
}
