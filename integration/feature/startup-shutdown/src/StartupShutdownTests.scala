package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object StartupShutdownTests extends UtestIntegrationTestSuite {
  // Ensure that you can quickly cycle through startup and shutdown without failures.
  val tests: Tests = Tests {
    test("rapid_cycle") - integrationTest { tester =>
      if (daemonMode) {
        val numCycles = 7

        for (index <- 1 to numCycles) {
          println(s"Cycle $index")

          val result1 = tester.eval(("resolve", "_"))
          assert(result1.isSuccess)

          // No point in running shutdown on the last cycle, as the test suite will shut it down.
          if (index != numCycles) {
            val result2 = tester.eval("shutdown")
            assert(result2.isSuccess)
          }
        }
      }
    }
  }
}
