package mill.pythonlib

import mill.Task
import mill.Command
import mill.TaskModule
import mill.T

trait TestModule extends TaskModule {
  import TestModule.TestResult

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * @see [[testCached]]
   */
  def test(args: String*): Command[Seq[TestResult]] =
    Task.Command {
      testTask(Task.Anon { args })()
    }

  /**
   * Args to be used by [[testCached]].
   */
  def testCachedArgs: T[Seq[String]] = Task { Seq[String]() }

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * If no input has changed since the last run, no test were executed.
   * @see [[test()]]
   */
  def testCached: T[Seq[TestResult]] = Task {
    testTask(testCachedArgs)()
  }

  /**
   * The actual task shared by `test`-tasks.
   */
  protected def testTask(args: Task[Seq[String]]): Task[Seq[TestResult]]

  override def defaultCommandName() = "test"
}

object TestModule {

  // TODO: this is a dummy for now, however we should look into re-using
  // mill.testrunner.TestResults
  type TestResult = Unit

  /** TestModule that uses Python's standard unittest module to run tests. */
  trait Unittest extends PythonModule with TestModule {
    protected def testTask(args: Task[Seq[String]]) = Task.Anon {
      val testArgs = if (args().isEmpty) {
        Seq("discover") ++ sources().flatMap(pr => Seq("-s", pr.path.toString))
      } else {
        args()
      }
      runner().run(
        ("-m", "unittest", testArgs, "-v")
      )
      Seq()
    }
  }

  /** TestModule that uses pytest to run tests. */
  trait Pytest extends PythonModule with TestModule {

    override def pythonDeps: T[Seq[String]] = T {
      super.pythonDeps() ++ Seq("pytest==8.3.3")
    }

    protected def testTask(args: Task[Seq[String]]) = Task.Anon {
      runner().run(
        (
          // format: off
          "-m", "pytest",
          "-o", s"cache_dir=${Task.dest / "cache"}",
          sources().map(_.path),
          args()
          // format: in
        )
      )
      Seq()
    }
  }

}
