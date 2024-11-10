package mill.scalalib

import mill.api.{Ctx, PathRef, Result}
import mill.define.{Command, Task, TaskModule}
import mill.scalalib.bsp.{BspBuildTarget, BspModule}
import mill.testrunner.{Framework, TestArgs, TestResult, TestRunner, TestRunnerUtils}
import mill.util.Jvm
import mill.{Agg, T}
import mill.testrunner.DiscoverTestsMain

trait TestModule
    extends TestModule.JavaModuleBase
    with WithZincWorker
    with RunModule
    with TaskModule {

  // FIXME: The `compile` is no longer needed, but we keep it for binary compatibility (0.11.x)
  def compile: T[mill.scalalib.api.CompilationResult]

  override def defaultCommandName() = "test"

  /**
   * The classpath containing the tests. This is most likely the output of the compilation target.
   * By default this uses the result of [[localRunClasspath]], which is most likely the result of a local compilation.
   */
  def testClasspath: T[Seq[PathRef]] = Task { localRunClasspath() }

  /**
   * The test framework to use.
   *
   * For convenience, you can also mix-in one of these predefined traits:
   * - [[TestModule.Junit4]]
   * - [[TestModule.Junit5]]
   * - [[TestModule.Munit]]
   * - [[TestModule.ScalaTest]]
   * - [[TestModule.Specs2]]
   * - [[TestModule.TestNg]]
   * - [[TestModule.Utest]]
   * - [[TestModule.Weaver]]
   * - [[TestModule.ZioTest]]
   */
  def testFramework: T[String]

  def discoveredTestClasses: T[Seq[String]] = Task {
    val classes = if (zincWorker().javaHome().isDefined) {
      val discverTestsMainArgs = DiscoverTestsMain.Args(
        classLoaderClasspath = runClasspath().map(_.path),
        testClasspath = testClasspath().map(_.path),
        testFramework = testFramework()
      )
      val argsFile = T.dest / "DiscoverTestsMainArgs"
      os.write(argsFile, upickle.default.write(discverTestsMainArgs))

      val process = Jvm.callSubprocess(
        mainClass = "mill.testrunner.DiscoverTestsMain",
        classPath = zincWorker().scalalibClasspath().map(_.path),
        mainArgs = Seq(argsFile.toString),
        javaHome = zincWorker().javaHome().map(_.path),
        streamOut = false
      )
      if (process.exitCode == 0) {
        process.out.lines()
      } else {
        throw new Exception("Discover tests subprocess failed (exit code " + process.exitCode + ")")
      }
    } else {
      Jvm.inprocess(
        runClasspath().map(_.path),
        classLoaderOverrideSbtTesting = true,
        isolated = true,
        closeContextClassLoaderWhenDone = true,
        cl => {
          val framework = Framework.framework(testFramework())(cl)
          val classes = TestRunnerUtils.discoverTests(cl, framework, testClasspath().map(_.path))
          classes.toSeq.map(_._1.getName())
            .map {
              case s if s.endsWith("$") => s.dropRight(1)
              case s => s
            }
        }
      )
    }
    classes.sorted
  }

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * @see [[testCached]]
   */
  def test(args: String*): Command[(String, Seq[TestResult])] =
    Task.Command {
      testTask(Task.Anon { args }, Task.Anon { Seq.empty[String] })()
    }

  def getTestEnvironmentVars(args: String*): Command[(String, String, String, Seq[String])] = {
    Task.Command {
      getTestEnvironmentVarsTask(Task.Anon { args })()
    }
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
  def testCached: T[(String, Seq[TestResult])] = Task {
    testTask(testCachedArgs, Task.Anon { Seq.empty[String] })()
  }

  /**
   * How the test classes in this module will be split into multiple JVM processes
   * and run in parallel during testing. Defaults to all of them running in one process
   * sequentially, but can be overriden to split them into separate groups that run
   * in parallel.
   */
  def testForkGrouping: T[Seq[Seq[String]]] = Task {
    Seq(discoveredTestClasses())
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
  def testOnly(args: String*): Command[(String, Seq[TestResult])] = {
    val (selector, testArgs) = args.indexOf("--") match {
      case -1 => (args, Seq.empty)
      case pos =>
        val (s, t) = args.splitAt(pos)
        (s, t.tail)
    }
    Task.Command {
      testTask(Task.Anon { testArgs }, Task.Anon { selector })()
    }
  }

  /**
   * Controls whether the TestRunner should receive it's arguments via an args-file instead of a as long parameter list.
   * Defaults to what `runUseArgsFile` return.
   */
  def testUseArgsFile: T[Boolean] = Task { runUseArgsFile() || scala.util.Properties.isWin }

  /**
   * Sets the file name for the generated JUnit-compatible test report.
   * If None is set, no file will be generated.
   */
  def testReportXml: T[Option[String]] = T(Some("test-report.xml"))

  /**
   * Returns a Tuple where the first element is the main-class, second and third are main-class-arguments and the forth is classpath
   */
  private def getTestEnvironmentVarsTask(args: Task[Seq[String]])
      : Task[(String, String, String, Seq[String])] =
    Task.Anon {
      val mainClass = "mill.testrunner.entrypoint.TestRunnerMain"
      val outputPath = T.dest / "out.json"
      val selectors = Seq.empty

      val testArgs = TestArgs(
        framework = testFramework(),
        classpath = runClasspath().map(_.path),
        arguments = args(),
        sysProps = Map.empty,
        outputPath = outputPath,
        colored = T.log.colored,
        testCp = testClasspath().map(_.path),
        home = T.home,
        globSelectors = selectors
      )

      val argsFile = T.dest / "testargs"
      os.write(argsFile, upickle.default.write(testArgs))

      val testRunnerClasspathArg =
        zincWorker().scalalibClasspath()
          .map(_.path.toNIO.toUri.toURL).mkString(",")

      val cp = (runClasspath() ++ zincWorker().testrunnerEntrypointClasspath()).map(_.path.toString)

      Result.Success((mainClass, testRunnerClasspathArg, argsFile.toString, cp))
    }

  /**
   * Whether or not to use the test task destination folder as the working directory
   * when running tests. `true` means test subprocess run in the `.dest/sandbox` folder of
   * the test task, providing better isolation and encouragement of best practices
   * (e.g. not reading/writing stuff randomly from the project source tree). `false`
   * means the test subprocess runs in the project root folder, providing weaker
   * isolation.
   */
  def testSandboxWorkingDir: T[Boolean] = true

  /**
   * The actual task shared by `test`-tasks that runs test in a forked JVM.
   */
  protected def testTask(
      args: Task[Seq[String]],
      globSelectors: Task[Seq[String]]
  ): Task[(String, Seq[TestResult])] =
    Task.Anon {
      TestModuleUtil.runTests(
        testUseArgsFile(),
        forkArgs(),
        globSelectors(),
        zincWorker().scalalibClasspath(),
        resources(),
        testFramework(),
        runClasspath(),
        testClasspath(),
        args(),
        testForkGrouping(),
        zincWorker().testrunnerEntrypointClasspath(),
        forkEnv(),
        testSandboxWorkingDir(),
        forkWorkingDir(),
        testReportXml(),
        zincWorker().javaHome().map(_.path)
      )
    }

  /**
   * Discovers and runs the module's tests in-process in an isolated classloader,
   * reporting the results to the console
   */
  def testLocal(args: String*): Command[(String, Seq[TestResult])] = Task.Command {
    val (doneMsg, results) = TestRunner.runTestFramework(
      Framework.framework(testFramework()),
      runClasspath().map(_.path),
      Agg.from(testClasspath().map(_.path)),
      args,
      T.testReporter
    )
    TestModule.handleResults(doneMsg, results, T.ctx(), testReportXml())
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

  /**
   * TestModule using TestNG Framework to run tests.
   * You need to provide the testng dependency yourself.
   */
  trait TestNg extends TestModule {
    override def testFramework: T[String] = "mill.testng.TestNGFramework"
    override def ivyDeps: T[Agg[Dep]] = Task {
      super.ivyDeps() ++ Agg(
        ivy"com.lihaoyi:mill-contrib-testng:${mill.api.BuildInfo.millVersion}"
      )
    }
  }

  /**
   * TestModule that uses JUnit 4 Framework to run tests.
   * You may want to provide the junit dependency explicitly to use another version.
   */
  trait Junit4 extends TestModule {
    override def testFramework: T[String] = "com.novocode.junit.JUnitFramework"
    override def ivyDeps: T[Agg[Dep]] = Task {
      super.ivyDeps() ++ Agg(ivy"${mill.scalalib.api.Versions.sbtTestInterface}")
    }
  }

  /**
   * TestModule that uses JUnit 5 Framework to run tests.
   * You may want to provide the junit dependency explicitly to use another version.
   */
  trait Junit5 extends TestModule {
    override def testFramework: T[String] = "com.github.sbt.junit.jupiter.api.JupiterFramework"
    override def ivyDeps: T[Agg[Dep]] = Task {
      super.ivyDeps() ++ Agg(ivy"${mill.scalalib.api.Versions.jupiterInterface}")
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
  trait Specs2 extends ScalaModuleBase with TestModule {
    override def testFramework: T[String] = "org.specs2.runner.Specs2Framework"
    override def scalacOptions = Task {
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

  /**
   * TestModule that uses Weaver to run tests.
   * You need to provide the weaver dependencies yourself.
   * https://github.com/disneystreaming/weaver-test
   */
  trait Weaver extends TestModule {
    override def testFramework: T[String] = "weaver.framework.CatsEffect"
  }

  /**
   * TestModule that uses ZIO Test Framework to run tests.
   * You need to provide the zio-test dependencies yourself.
   */
  trait ZioTest extends TestModule {
    override def testFramework: T[String] = "zio.test.sbt.ZTestFramework"
  }

  @deprecated("Use other overload instead", "Mill after 0.10.2")
  def handleResults(
      doneMsg: String,
      results: Seq[TestResult]
  ): Result[(String, Seq[TestResult])] = handleResults(doneMsg, results, None)

  def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: Option[Ctx.Env]
  ): Result[(String, Seq[TestResult])] = TestModuleUtil.handleResults(doneMsg, results, ctx)

  def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: Ctx.Env with Ctx.Dest,
      testReportXml: Option[String],
      props: Option[Map[String, String]] = None
  ): Result[(String, Seq[TestResult])] =
    TestModuleUtil.handleResults(doneMsg, results, ctx, testReportXml, props)

  trait JavaModuleBase extends BspModule {
    def ivyDeps: T[Agg[Dep]] = Agg.empty[Dep]
    def resources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }
  }

  trait ScalaModuleBase extends mill.Module {
    def scalacOptions: T[Seq[String]] = Seq.empty[String]
  }

}
