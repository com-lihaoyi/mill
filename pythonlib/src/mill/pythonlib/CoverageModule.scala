package mill.pythonlib

import mill._

/**
 * Code coverage via Python's [coverage](https://coverage.readthedocs.io/)
 * package.
 *
 * Mix this in to any module to run them
 */
trait CoverageModule extends PythonModule {

  override def pythonToolDeps = Task {
    super.pythonToolDeps() ++ Seq("coverage>=7.6.10")
  }

  // override def pythonOptions = Task {
  //   Seq("-m", "coverage", "run") ++ super.pythonOptions()
  // }

  def report = Task {

    pythonExe()

  }




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
