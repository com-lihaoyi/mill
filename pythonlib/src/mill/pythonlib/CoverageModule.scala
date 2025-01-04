package mill.pythonlib

import mill._

/**
 * Code coverage
 */
trait CoverageModule extends PythonModule {

  trait CoverageTests extends PythonTests {
    override def pythonToolDeps = Task {
      super.pythonToolDeps() ++ Seq("coverage>=7.6.9")
    }

    override def pythonOptions = Task {
      Seq("-m", "coverage", "run") ++ super.pythonOptions()
    }

    def report = Task {
      // testCached()
    }

  }


}
