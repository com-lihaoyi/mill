package mill.pythonlib

import mill._

/**
 * Code coverage via Python's [coverage](https://coverage.readthedocs.io/)
 * package.
 *
 * ** Note that this is a helper trait, and you are unlikely to use this
 * directly. If you're looking for including coverage across tests in your
 * project, please use [[CoverageTests]] instead! **
 *
 * If you do want to use this module directly, please be aware that analyzing
 * code coverage introduces "non-linear" changes to the execution task flow, and
 * you will need to respect the following contract:
 *
 * 1. This trait defines a location where coverage data must be saved.
 *
 * 2. You need to define a `coverageTask` which is responsible for creating
 *    coverage data in the before mentioned location. How this is done is up to
 *    you. As an example, the [[CoverageTests]] module modifies `pythonOptions`
 *    to prepend a `-m coverage` command line argument.
 *
 * 3. This trait defines methods that will a) invoke the coverage task b) assume
 *    report data exists in the predefined location c) use that data to generate
 *    coverage reports.
 */
trait CoverageModule extends PythonModule {

  override def pythonToolDeps = Task {
    super.pythonToolDeps() ++ Seq("coverage>=7.6.10")
  }

  /**
   * The *location* (not the ref), where the coverage report lives. This
   * intentionally does not return a PathRef, since it will be populated from
   * other places.
   */
  def coverageDataFile: T[os.Path] = Task { Task.dest / "coverage" }

  /**
   * The task to run to generate the coverage report.
   *
   * This task must generate a coverage report into the output directory of
   * [[coverageDataFile]]. It is required that this file be readable as soon
   * as this task returns.
   */
  def coverageTask: Task[?]

  private case class CoverageReporter(
      interp: os.Path,
      env: Map[String, String]
  ) {
    def run(command: String, args: Seq[String]): Unit =
      os.call(
        (
          interp,
          "-m",
          "coverage",
          command,
          args
        ),
        env = env,
        stdout = os.Inherit
      )
  }

  private def coverageReporter = Task.Anon {
    CoverageReporter(
      pythonExe().path,
      Map(
        "COVERAGE_FILE" -> coverageDataFile().toString
      )
    )
  }

  /**
   * Generate a coverage report.
   *
   * This command accepts arguments understood by `coverage report`. For
   * example, you can cause it to fail if a certain coverage threshold is not
   * met: `mill coverageReport --fail-under 90`
   */
  def coverageReport(args: String*): Command[Unit] = Task.Command {
    coverageTask()
    coverageReporter().run("report", args)
  }

  /**
   * Generate a HTML version of the coverage report.
   */
  def coverageHtml(args: String*): Command[Unit] = Task.Command {
    coverageTask()
    coverageReporter().run("html", args)
  }

  /**
   * Generate a JSON version of the coverage report.
   */
  def coverageJson(args: String*): Command[Unit] = Task.Command {
    coverageTask()
    coverageReporter().run("json", args)
  }

  /**
   * Generate an XML version of the coverage report.
   */
  def coverageXml(args: String*): Command[Unit] = Task.Command {
    coverageTask()
    coverageReporter().run("xml", args)
  }

  /**
   * Generate an LCOV version of the coverage report.
   */
  def coverageLcov(args: String*): Command[Unit] = Task.Command {
    coverageTask()
    coverageReporter().run("lcov", args)
  }

}

/** Analyze code coverage, starting from tests. */
trait CoverageTests extends CoverageModule with TestModule {

  override def pythonOptions = Task {
    Seq("-m", "coverage", "run", "--data-file", coverageDataFile().toString) ++
      super.pythonOptions()
  }

  override def coverageTask = testCached

}
