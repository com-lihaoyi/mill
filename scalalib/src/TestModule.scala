package mill.scalalib

import mill.{Agg, BuildInfo, T}
import mill.define.{Command, Task, TaskModule}
import mill.eval.Result
import mill.modules.Jvm

trait TestModule extends JavaModule with TaskModule {
  override def defaultCommandName() = "test"

  /**
   * What test frameworks to use.
   */
  @deprecated("Use testFramework instead.", "mill after 0.9.6")
  def testFrameworks: T[Seq[String]] = T { Seq.empty[String] }

  /**
   * The test framework to use.
   */
  def testFramework: T[String] = T {
    val frameworks = testFrameworks()
    val msg =
      "Target testFrameworks is deprecated. Please use target testFramework or use on of the " +
        "predefined TestModules: TestNg, Junit, Scalatest, ..."
    if (frameworks.size != 1) {
      Result.Failure(
        "Since mill after-0.9.6 only one test framework per TestModule is supported. " ++ msg)
    } else {
      T.log.error(msg)
      Result.Success(frameworks.head)
    }
  }

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * @see [[testCached]]
   */
  def test(args: String*): Command[(String, Seq[TestRunner.Result])] =
    T.command {
      testTask(T.task { args })()
    }

  /**
   * Args to be used by [[testCached]].
   */
  def testCachedArgs: T[Seq[String]] = T { Seq[String]() }

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * If no input has changed since the last run, no test were executed.
   * @see [[test()]]
   */
  def testCached: T[(String, Seq[TestRunner.Result])] = T {
    testTask(testCachedArgs)()
  }

  /** Controls whether the TestRunner should receive it's arguments via an args-file instead of a as long parameter list.
   * Defaults to `true` on Windows, as Windows has a rather short parameter length limit.
   * */
  def testUseArgsFile: T[Boolean] = T { scala.util.Properties.isWin }

  protected def testTask(
      args: Task[Seq[String]]): Task[(String, Seq[TestRunner.Result])] =
    T.task {
      val outputPath = T.dest / "out.json"
      val useArgsFile = testUseArgsFile()

      val (jvmArgs, props: Map[String, String]) = if (useArgsFile) {
        val (props, jvmArgs) = forkArgs().partition(_.startsWith("-D"))
        val sysProps =
          props
            .map(_.drop(2).split("[=]", 2))
            .map {
              case Array(k, v) => k -> v
              case Array(k)    => k -> ""
            }
            .toMap

        jvmArgs -> sysProps
      } else {
        forkArgs() -> Map()
      }

      val testArgs = TestRunner.TestArgs(
        framework = testFramework(),
        classpath = runClasspath().map(_.path.toString()),
        arguments = args(),
        sysProps = props,
        outputPath = outputPath.toString(),
        colored = T.log.colored,
        testCp = compile().classes.path.toString(),
        homeStr = T.home.toString()
      )

      val mainArgs = if (useArgsFile) {
        val argsFile = T.dest / "testargs"
        Seq(testArgs.writeArgsFile(argsFile))
      } else {
        testArgs.toArgsSeq
      }

      Jvm.runSubprocess(
        mainClass = "mill.scalalib.TestRunner",
        classPath = zincWorker.scalalibClasspath().map(_.path),
        jvmArgs = jvmArgs,
        envArgs = forkEnv(),
        mainArgs = mainArgs,
        workingDir = forkWorkingDir(),
        useCpPassingJar = useArgsFile
      )

      if (!os.exists(outputPath)) Result.Failure("Test execution failed.")
      else
        try {
          val jsonOutput = ujson.read(outputPath.toIO)
          val (doneMsg, results) =
            upickle.default.read[(String, Seq[TestRunner.Result])](jsonOutput)
          TestModule.handleResults(doneMsg, results)
        } catch {
          case e: Throwable =>
            Result.Failure("Test reporting failed: " + e)
        }
    }

  /**
   * Discovers and runs the module's tests in-process in an isolated classloader,
   * reporting the results to the console
   */
  def testLocal(args: String*) = T.command {
    val outputPath = T.dest / "out.json"

    val (doneMsg, results) = TestRunner.runTestFramework(
      TestRunner.framework(testFramework()),
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args,
      T.testReporter
    )

    TestModule.handleResults(doneMsg, results)

  }
}

object TestModule {

  /** TestModule using TestNG Framework to run tests. */
  trait TestNg extends TestModule {
    /** The TestNG version to use. */
    def testNgVersion: T[String]
    override def testFramework: T[String] = "mill.testng.TestNGFramework"
    override def ivyDeps: T[Agg[Dep]] = T {
      super.ivyDeps() ++ Agg(
        ivy"com.lihaoyi:mill-contrib-testng_2.13:${mill.BuildInfo.millVersion}",
        ivy"org.testng:testng:${testNgVersion()}"
      )
    }
  }

  /** TestModule that uses JUnit 4 Framework to run tests. */
  trait Junit4 extends TestModule {
    /** The JUnit version to use. */
    def junitVersion: T[String]
    override def testFramework: T[String] = "com.novocode.junit.JUnitFramework"
    override def ivyDeps: T[Agg[Dep]] = T {
      super.ivyDeps() ++ Agg(
        ivy"com.novocode:junit-interface:0.11",
        ivy"junit:junit:${junitVersion()}"
      )
    }
  }

  /**
   * TestModule that uses Scalatest Framework to run tests.
   * You need to provide the scalatest dependencies yourself.
   */
  trait Scalatest extends TestModule {
    override def testFramework: T[String] = "org.scalatest.tools.Framework"
  }

  /**
   * TestModule that uses Specs2 Framework to run tests.
   * You need to provide the specs2 dependencies yourself.
   */
  trait Specs2 extends TestModule {
    override def testFramework: T[String] = "org.specs2.runner.Specs2Framework"
  }

  def handleResults(doneMsg: String, results: Seq[TestRunner.Result])
    : Result[(String, Seq[TestRunner.Result])] = {

    val badTests: Seq[TestRunner.Result] =
      results.filter(x => Set("Error", "Failure").contains(x.status))
    if (badTests.isEmpty) {
      Result.Success((doneMsg, results))
    } else {
      val reportCount = 5
      val suffix =
        if (badTests.length <= reportCount) ""
        else s"\n  and ${badTests.length - reportCount} more ..."

      val msg = s"${badTests.size} tests failed: ${badTests
        .take(reportCount)
        .map(t => s"${t.fullyQualifiedName} ${t.selector}")
        .mkString("\n  ")}$suffix"

      Result.Failure(msg, Some((doneMsg, results)))
    }
  }
}
