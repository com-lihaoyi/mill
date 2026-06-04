package mill.pythonlib

import mill.Task
import mill.Command
import mill.DefaultTaskModule
import mill.T
import mill.api.{BuildCtx, PathRef}

trait TestModule extends DefaultTaskModule {
  import TestModule.TestResult

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * @see [[testCached]]
   */
  def testForked(args: String*): Command[Seq[TestResult]] =
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
   *
   * @see [[testForked()]]
   */
  def testCached: T[Seq[TestResult]] = Task {
    testTask(testCachedArgs)()
  }

  /**
   * The actual task shared by `test`-tasks.
   */
  protected def testTask(args: Task[Seq[String]]): Task[Seq[TestResult]]

  override def defaultTask() = "testForked"
}

object TestModule {

  // TODO: this is a dummy for now, however we should look into re-using
  // mill.javalib.testrunner.TestResults
  type TestResult = Unit

  /** TestModule that uses Python's standard unittest module to run tests. */
  trait Unittest extends PythonModule with TestModule {
    protected def testTask(args: Task[Seq[String]]) = Task.Anon {
      val testArgs = if (args().isEmpty) {
        // Absolute (symlink-resolved) paths: workingDir is the workspace root, and
        // unittest/pytest `chdir` into test directories during discovery/collection, so a
        // relativized `../mill-workspace`/through-forwarder path would not resolve (and would
        // double-alias, making the runner collect tests twice).
        Seq("discover") ++ sources().flatMap(pr =>
          Seq("-s", PathRef.toResolvedPathString(pr.path))
        )
      } else {
        args()
      }
      runner().run(
        ("-m", "unittest", testArgs, "-v"),
        workingDir = BuildCtx.workspaceRoot
      )
      Seq()
    }
  }

  /** TestModule that uses pytest to run tests. */
  trait Pytest extends PythonModule with TestModule {

    override def pythonDeps: T[Seq[String]] = Task {
      super.pythonDeps() ++ Seq("pytest==8.3.3")
    }

    protected def testTask(args: Task[Seq[String]]) = Task.Anon {
      runner().run(
        (
          // format: off
          "-m", "pytest",
          // Absolute (symlink-resolved) paths: pytest `chdir`s into test directories during
          // collection, so a relativized/through-forwarder path would not resolve and would
          // double-alias (causing tests to be collected twice under different path strings).
          "-o", s"cache_dir=${PathRef.toResolvedPathString(Task.dest / "cache")}", "-v",
          sources().map(pr => PathRef.toResolvedPathString(pr.path)),
          args()
          // format: in
        ),
        workingDir = BuildCtx.workspaceRoot
      )
      Seq()
    }
  }

}
