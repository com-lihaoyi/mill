package mill.integration

import utest._

object SubprocessStdoutTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test {
      val res1 = evalStdCombined("inheritInterleaved").out
      // Make sure that when a lot of printed/inherited stdout/stderr is printed
      // in quick succession, the output ordering is preserved and it doesn't get
      // jumbled up
      assert(
        res1.contains(
          s"""print stdout1
             |proc stdout1
             |print stderr1
             |proc stderr1
             |print stdout2
             |proc stdout2
             |print stderr2
             |proc stderr2
             |print stdout3
             |proc stdout3
             |print stderr3
             |proc stderr3
             |print stdout4
             |proc stdout4
             |print stderr4
             |proc stderr4
             |print stdout5
             |proc stdout5
             |print stderr5
             |proc stderr5
             |print stdout6
             |proc stdout6
             |print stderr6
             |proc stderr6
             |print stdout7
             |proc stdout7
             |print stderr7
             |proc stderr7
             |print stdout8
             |proc stdout8
             |print stderr8
             |proc stderr8
             |print stdout9
             |proc stdout9
             |print stderr9
             |proc stderr9""".stripMargin
        )
      )

      // Make sure subprocess output that isn't captures by all of Mill's stdout/stderr/os.Inherit
      // redirects still gets pikced up from the stdout/stderr log files and displayed. They may
      // be out of order from the original Mill stdout/stderr, but they should still at least turn
      // up in the console somewhere and not disappear
      //
      val res2 = evalStdCombined("inheritRaw").out
      if (integrationTestMode != "fork") {
        // For `fork` tests, which represent `-i`/`--interactive`/`--no-server`, the output should
        // be properly ordered since it all comes directly from the stdout/stderr of the same process
        assert(
          res2.contains(
            """print stdoutRaw
              |proc stdoutRaw
              |print stderrRaw
              |proc stderrRaw""".stripMargin
          )
        )
      } else {
        // Note that it should be out of order, because both `print`s will be captured and logged first,
        // whereas the two `proc` outputs will get sent to their respective log files and only noticed
        // a few milliseconds later as the files are polled for updates
        assert(
          res2.contains(
            """print stdoutRaw
              |print stderrRaw
              |proc stdoutRaw
              |proc stderrRaw""".stripMargin
          )
        )
      }
    }
  }
}
