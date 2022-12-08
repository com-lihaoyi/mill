package mill.scalalib

import mill.{Agg, T}
import mill.define.{Command, Task, TaskModule}
import mill.api.{Ctx, Result}
import mill.modules.Jvm
import mill.scalalib.bsp.{BspBuildTarget, BspModule}
import mill.testrunner.TestRunner

trait TestModule extends JavaModule with TaskModule {
  override def defaultCommandName() = "test"

  /**
   * The test framework to use.
   */
  def testFramework: T[String]

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * @see [[testCached]]
   */
  def test(args: String*): Command[(String, Seq[TestRunner.Result])] =
    T.command {
      testTask(T.task { args }, T.task { Seq.empty[String] })()
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
    testTask(testCachedArgs, T.task { Seq.empty[String] })()
  }

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * Arguments before "--" will be used as wildcard selector to select
   * test classes, arguments after "--" will be passed as regular arguments.
   * `testOnly *foo foobar bar* -- arguments` will test only classes with name
   * (includes package name) 1. end with "foo", 2. exactly "foobar", 3. start
   * with "bar", with "arguments" as arguments passing to test framework.
   */
  def testOnly(args: String*): Command[(String, Seq[TestRunner.Result])] = {
    val splitAt = args.indexOf("--")
    val (selector, testArgs) =
      if (splitAt == -1) (args, Seq.empty)
      else {
        val (s, t) = args.splitAt(splitAt)
        (s, t.tail)
      }
    T.command {
      testTask(T.task { testArgs }, T.task { selector })()
    }
  }

  /**
   * Controls whether the TestRunner should receive it's arguments via an args-file instead of a as long parameter list.
   * Defaults to `true` on Windows, as Windows has a rather short parameter length limit.
   */
  def testUseArgsFile: T[Boolean] = T { runUseArgsFile() || scala.util.Properties.isWin }

  protected def testTask(
      args: Task[Seq[String]],
      globSelectors: Task[Seq[String]]
  ): Task[(String, Seq[TestRunner.Result])] =
    T.task {
      val outputPath = T.dest / "out.json"
      val useArgsFile = testUseArgsFile()

      val (jvmArgs, props: Map[String, String]) =
        if (useArgsFile) {
          val (props, jvmArgs) = forkArgs().partition(_.startsWith("-D"))
          val sysProps =
            props
              .map(_.drop(2).split("[=]", 2))
              .map {
                case Array(k, v) => k -> v
                case Array(k) => k -> ""
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
        homeStr = T.home.toString(),
        globSelectors = globSelectors()
      )

      val mainArgs =
        if (useArgsFile) {
          val argsFile = T.dest / "testargs"
          Seq(testArgs.writeArgsFile(argsFile))
        } else {
          testArgs.toArgsSeq
        }

      Jvm.runSubprocess(
        mainClass = "mill.testrunner.TestRunner",
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
          TestModule.handleResults(doneMsg, results, Some(T.ctx()))
        } catch {
          case e: Throwable =>
            Result.Failure("Test reporting failed: " + e)
        }
    }

  /**
   * Discovers and runs the module's tests in-process in an isolated classloader,
   * reporting the results to the console
   */
  def testLocal(args: String*): Command[(String, Seq[TestRunner.Result])] = T.command {
    val (doneMsg, results) = TestRunner.runTestFramework(
      TestRunner.framework(testFramework()),
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args,
      T.testReporter
    )
    TestModule.handleResults(doneMsg, results, Some(T.ctx()))
  }

  override def bspBuildTarget: BspBuildTarget = {
    val parent = super.bspBuildTarget
    parent.copy(
      canTest = true,
      tags = Seq(BspModule.Tag.Test)
    )
  }
}

object TestModule {
  private val FailedTestReportCount = 5

  /**
   * TestModule using TestNG Framework to run tests.
   * You need to provide the testng dependency yourself.
   */
  trait TestNg extends TestModule {
    override def testFramework: T[String] = "mill.testng.TestNGFramework"
    override def ivyDeps: T[Agg[Dep]] = T {
      super.ivyDeps() ++ Agg(
        ivy"com.lihaoyi:mill-contrib-testng:${mill.BuildInfo.millVersion}"
      )
    }
  }

  /**
   * TestModule that uses JUnit 4 Framework to run tests.
   * You may want to provide the junit dependency explicitly to use another version.
   */
  trait Junit4 extends TestModule {
    override def testFramework: T[String] = "com.novocode.junit.JUnitFramework"
    override def ivyDeps: T[Agg[Dep]] = T {
      super.ivyDeps() ++ Agg(ivy"com.novocode:junit-interface:0.11")
    }
  }

  /**
   * TestModule that uses JUnit 5 Framework to run tests.
   * You may want to provide the junit dependency explicitly to use another version.
   */
  trait Junit5 extends TestModule {
    override def testFramework: T[String] = "net.aichler.jupiter.api.JupiterFramework"
    override def ivyDeps: T[Agg[Dep]] = T {
      super.ivyDeps() ++ Agg(ivy"net.aichler:jupiter-interface:0.9.0")
    }
  }

  /**
   * TestModule that uses ScalaTest Framework to run tests.
   * You need to provide the scalatest dependencies yourself.
   */
  trait ScalaTest extends TestModule {
    override def testFramework: T[String] = "org.scalatest.tools.Framework"
  }

  /**
   * TestModule that uses Specs2 Framework to run tests.
   * You need to provide the specs2 dependencies yourself.
   */
  trait Specs2 extends ScalaModule with TestModule {
    override def testFramework: T[String] = "org.specs2.runner.Specs2Framework"
    override def scalacOptions = T {
      super.scalacOptions() ++ Seq("-Yrangepos")
    }
  }

  /**
   * TestModule that uses UTest Framework to run tests.
   * You need to provide the utest dependencies yourself.
   */
  trait Utest extends TestModule {
    override def testFramework: T[String] = "utest.runner.Framework"
  }

  /**
   * TestModule that uses MUnit to run tests.
   * You need to provide the munit dependencies yourself.
   */
  trait Munit extends TestModule {
    override def testFramework: T[String] = "munit.Framework"
  }

  @deprecated("Use other overload instead", "Mill after 0.10.2")
  def handleResults(
      doneMsg: String,
      results: Seq[TestRunner.Result]
  ): Result[(String, Seq[TestRunner.Result])] = handleResults(doneMsg, results, None)

  def handleResults(
      doneMsg: String,
      results: Seq[TestRunner.Result],
      ctx: Option[Ctx.Env]
  ): Result[(String, Seq[TestRunner.Result])] = {

    val badTests: Seq[TestRunner.Result] =
      results.filter(x => Set("Error", "Failure").contains(x.status))
    if (badTests.isEmpty) {
      Result.Success((doneMsg, results))
    } else {
      val reportCount =
        if (ctx.fold(false)(_.env.contains("CI"))) badTests.length
        else FailedTestReportCount
      val suffix =
        if (badTests.length <= reportCount) ""
        else s"\n  and ${badTests.length - reportCount} more ..."

      val msg = s"${badTests.size} tests failed: ${badTests
          .take(reportCount)
          .map(t => s"${t.fullyQualifiedName} ${t.selector}")
          .mkString("\n  ", "\n  ", "")}$suffix"

      Result.Failure(msg, Some((doneMsg, results)))
    }
  }
}
