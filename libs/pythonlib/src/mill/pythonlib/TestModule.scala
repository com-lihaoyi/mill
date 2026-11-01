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

  private def missingSources(sourcePaths: Seq[PathRef]): Seq[PathRef] =
    sourcePaths.filterNot(path => os.exists(path.path))

  private def unittestTopLevel(sourceDirectory: os.Path): Option[os.Path] = {
    var packageDirectory = sourceDirectory
    var topLevel = sourceDirectory
    var foundPackage = false
    while (
      packageDirectory != packageDirectory / os.up &&
      os.exists(packageDirectory / "__init__.py")
    ) {
      foundPackage = true
      topLevel = packageDirectory / os.up
      packageDirectory = topLevel
    }
    Option.when(foundPackage)(topLevel)
  }

  // TODO: this is a dummy for now, however we should look into re-using
  // mill.javalib.testrunner.TestResults
  type TestResult = Unit

  /** TestModule that uses Python's standard unittest module to run tests. */
  trait Unittest extends PythonModule with TestModule {
    protected def testTask(args: Task[Seq[String]]) = Task.Anon {
      if (args().isEmpty) {
        val sourcePaths = sources()
        val missing = missingSources(sourcePaths)
        if (missing.nonEmpty) {
          Task.fail(s"Python test source paths do not exist: ${missing.map(_.path).mkString(", ")}")
        }
        val (sourceDirectories, sourceFiles) = sourcePaths.partition(path => os.isDir(path.path))
        val fileInvocations = sourceFiles
          .filter(path => path.path.last.startsWith("test") && path.path.ext == "py")
          .map { source =>
            val workspaceRoot = BuildCtx.workspaceRoot
            val sourcePath = source.path
            val sourceDirectory = sourcePath / os.up
            val bareTopLevel = this match {
              case bare: BarePythonModule =>
                val bareRoot = bare.moduleDir
                Option.when(sourcePath.startsWith(bareRoot)) {
                  if (bareRoot == workspaceRoot) workspaceRoot else bareRoot / os.up
                }
              case _ => None
            }
            val topLevel = bareTopLevel.orElse(unittestTopLevel(sourceDirectory))
            val moduleName = topLevel match {
              case Some(path) =>
                val relativeSegments = sourcePath.relativeTo(path).segments
                (relativeSegments.dropRight(1) :+ sourcePath.baseName).mkString(".")
              case None => sourcePath.baseName
            }
            (
              Seq(moduleName),
              Seq(topLevel.getOrElse(sourceDirectory))
            )
          }
        val invocations = sourceDirectories.map(source =>
          (
            Seq(
              "discover",
              "-s",
              source.path.toString
            ),
            Seq(source.path)
          )
        ) ++ fileInvocations

        for (((testArgs, sourcePythonPath), index) <- invocations.zipWithIndex) {
          val pythonPath =
            (sourcePythonPath ++ transitivePythonPath().map(_.path)).distinct
              .map(_.toString)
              .mkString(java.io.File.pathSeparator)
          runner().run(
            (if (index == 0) Nil else repeatedPythonOptions().toStringSeq) ++
              Seq("-m", "unittest") ++ testArgs ++ Seq("-v"),
            env = Map("PYTHONPATH" -> pythonPath),
            workingDir = BuildCtx.workspaceRoot
          )
        }
      } else {
        runner().run(
          Seq("-m", "unittest") ++ args() ++ Seq("-v"),
          workingDir = BuildCtx.workspaceRoot
        )
      }
      Seq()
    }
  }

  /** TestModule that uses pytest to run tests. */
  trait Pytest extends PythonModule with TestModule {

    override def pythonToolDeps: T[Seq[String]] = Task {
      super.pythonToolDeps() ++ Seq("pytest==9.1.1")
    }

    protected def testTask(args: Task[Seq[String]]) = Task.Anon {
      val sourcePaths = sources()
      val missing = missingSources(sourcePaths)
      if (missing.nonEmpty) {
        Task.fail(s"Python test source paths do not exist: ${missing.map(_.path).mkString(", ")}")
      }
      val testPaths = sourcePaths.map { source =>
        val escaped = source.path.toString.replace("\\", "\\\\").replace("\"", "\\\"")
        s"\"$escaped\""
      }.mkString(" ")
      runner().run(
        (
          // format: off
          "-m", "pytest",
          "-o", s"cache_dir=${Task.dest / "cache"}",
          "-o", s"testpaths=$testPaths",
          "-v",
          args()
          // format: in
        ),
        workingDir = BuildCtx.workspaceRoot
      )
      Seq()
    }
  }

}
