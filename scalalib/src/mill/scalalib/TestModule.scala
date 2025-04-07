package mill.scalalib

import mill.api.{Ctx, PathRef, Result}
import mill.define.{Command, Task, TaskModule}
import mill.scalalib.bsp.{BspBuildTarget, BspModule}
import mill.testrunner.{Framework, TestArgs, TestResult, TestRunner}
import mill.util.Jvm
import mill.T

trait TestModule
    extends TestModule.JavaModuleBase
    with WithJvmWorker
    with RunModule
    with TaskModule {

  override def defaultCommandName() = "testForked"

  /**
   * The classpath containing the tests. This is most likely the output of the compilation target.
   * By default, this uses the result of [[localRunClasspath]], which is most likely the result of a local compilation.
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
    val classes = if (jvmWorker().javaHome().isDefined) {
      Jvm.callProcess(
        mainClass = "mill.testrunner.DiscoverTestsMain",
        classPath = jvmWorker().scalalibClasspath().map(_.path).toVector,
        mainArgs =
          runClasspath().flatMap(p => Seq("--runCp", p.path.toString())) ++
            testClasspath().flatMap(p => Seq("--testCp", p.path.toString())) ++
            Seq("--framework", testFramework()),
        javaHome = jvmWorker().javaHome().map(_.path),
        stdin = os.Inherit,
        stdout = os.Pipe,
        cwd = Task.dest
      ).out.lines()
    } else {
      mill.testrunner.DiscoverTestsMain.main0(
        runClasspath().map(_.path),
        testClasspath().map(_.path),
        testFramework()
      )
    }
    classes.sorted
  }

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * @see [[testCached]]
   */
  def testForked(args: String*): Command[(String, Seq[TestResult])] =
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
   *
   * @see [[testForked()]]
   */
  def testCached: T[(String, Seq[TestResult])] = Task {
    testTask(testCachedArgs, Task.Anon { Seq.empty[String] })()
  }

  /**
   * How the test classes in this module will be split.
   * Test classes from different groups are ensured to never
   * run on the same JVM process, and therefore can be run in parallel.
   * When used in combination with [[testParallelism]],
   * every JVM test running process will guarantee to never claim tests
   * from different test groups.
   */
  def testForkGrouping: T[Seq[Seq[String]]] = Task {
    Seq(discoveredTestClasses())
  }

  /**
   * Whether to use the test parallelism to run tests in multiple JVM processes.
   * When used in combination with [[testForkGrouping]], every JVM test running process
   * will guarantee to never claim tests from different test groups.
   */
  def testParallelism: T[Boolean] = T(true)

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
   * Controls whether the TestRunner should receive its arguments via an args-file instead of a long parameter list.
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
      val outputPath = Task.dest / "out.json"
      val resultPath = Task.dest / "results.log"
      val selectors = Seq.empty

      val testArgs = TestArgs(
        framework = testFramework(),
        classpath = runClasspath().map(_.path).toVector,
        arguments = args(),
        sysProps = Map.empty,
        outputPath = outputPath,
        resultPath = resultPath,
        colored = Task.log.prompt.colored,
        testCp = testClasspath().map(_.path),
        globSelectors = Left(selectors)
      )

      val argsFile = Task.dest / "testargs"
      os.write(argsFile, upickle.default.write(testArgs))

      val testRunnerClasspathArg =
        jvmWorker().scalalibClasspath()
          .map(_.path.toNIO.toUri.toURL).mkString(",")

      val cp = (runClasspath() ++ jvmWorker().testrunnerEntrypointClasspath()).map(_.path.toString)

      Result.Success((mainClass, testRunnerClasspathArg, argsFile.toString, cp))
    }

  /**
   * Whether to use the test task destination folder as the working directory
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
      val testModuleUtil = new TestModuleUtil(
        testUseArgsFile(),
        forkArgs(),
        globSelectors(),
        jvmWorker().scalalibClasspath(),
        resources(),
        testFramework(),
        runClasspath(),
        testClasspath(),
        args(),
        testForkGrouping(),
        jvmWorker().testrunnerEntrypointClasspath(),
        forkEnv(),
        testSandboxWorkingDir(),
        forkWorkingDir(),
        testReportXml(),
        jvmWorker().javaHome().map(_.path),
        testParallelism()
      )
      testModuleUtil.runTests()
    }

  /**
   * Discovers and runs the module's tests in-process in an isolated classloader,
   * reporting the results to the console
   */
  def testLocal(args: String*): Command[(String, Seq[TestResult])] = Task.Command {
    val (doneMsg, results) = TestRunner.runTestFramework(
      Framework.framework(testFramework()),
      runClasspath().map(_.path),
      Seq.from(testClasspath().map(_.path)),
      args,
      Task.testReporter
    )
    TestModule.handleResults(doneMsg, results, Task.ctx(), testReportXml())
  }

  override def bspBuildTarget: BspBuildTarget = {
    val parent = super.bspBuildTarget
    parent.copy(
      canTest = true,
      tags = Seq(BspModule.Tag.Test)
    )
  }

  def bspBuildTargetScalaTestClasses = this match {
    case m: TestModule =>
      Task.Anon(Some((m.runClasspath(), m.testFramework(), m.testClasspath())))
    case _ =>
      Task.Anon(None)
  }
}

object TestModule {

  /**
   * TestModule using TestNG Framework to run tests.
   * You need to provide the testng dependency yourself.
   */
  trait TestNg extends TestModule {
    override def testFramework: T[String] = "mill.testng.TestNGFramework"
    override def mandatoryIvyDeps: T[Seq[Dep]] = Task {
      super.mandatoryIvyDeps() ++ Seq(
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
    override def mandatoryIvyDeps: T[Seq[Dep]] = Task {
      super.mandatoryIvyDeps() ++ Seq(ivy"${mill.scalalib.api.Versions.sbtTestInterface}")
    }
  }

  /**
   * TestModule that uses JUnit 5 Framework to run tests.
   * You may want to provide the junit dependency explicitly to use another version.
   */
  trait Junit5 extends TestModule {
    override def testFramework: T[String] = "com.github.sbt.junit.jupiter.api.JupiterFramework"
    override def mandatoryIvyDeps: T[Seq[Dep]] = Task {
      super.mandatoryIvyDeps() ++ Seq(ivy"${mill.scalalib.api.Versions.jupiterInterface}")
    }

    private val classesDir: Task[Option[os.Path]] = this match {
      case withCompileTask: JavaModule => Task.Anon {
          Some(withCompileTask.compile().classes.path)
        }
      case m => Task.Anon {
          m.testClasspath().map(_.path).find { path =>
            os.exists(path) && os.walk.stream(path).exists(p => os.isFile(p) && p.ext == "class")
          }
        }
    }

    /**
     * Overridden since Junit5 has its own discovery mechanism.
     *
     * This is basically a re-implementation of sbt's plugin for Junit5 test
     * discovery mechanism. See
     * https://github.com/sbt/sbt-jupiter-interface/blob/468d4f31f1f6ce8529fff8a8804dd733974c7686/src/plugin/src/main/scala/com/github/sbt/junit/jupiter/sbt/JupiterPlugin.scala#L97C15-L118
     * for details.
     *
     * Note that we access the test discovery via reflection, to avoid mill
     * itself having a dependency on Junit5. Hence, if you remove the
     * `sbt-jupiter-interface` dependency from `ivyDeps`, make sure to also
     * override this method.
     */
    override def discoveredTestClasses: T[Seq[String]] = Task {
      Jvm.withClassLoader(
        classPath = runClasspath().map(_.path).toVector,
        sharedPrefixes = Seq("sbt.testing.")
      ) { classLoader =>
        val builderClass: Class[?] =
          classLoader.loadClass("com.github.sbt.junit.jupiter.api.JupiterTestCollector$Builder")
        val builder = builderClass.getConstructor().newInstance()

        classesDir().foreach { path =>
          builderClass.getMethod("withClassDirectory", classOf[java.io.File]).invoke(
            builder,
            path.wrapped.toFile
          )
        }

        builderClass.getMethod("withRuntimeClassPath", classOf[Array[java.net.URL]]).invoke(
          builder,
          testClasspath().map(_.path.wrapped.toUri().toURL()).toArray
        )
        builderClass.getMethod("withClassLoader", classOf[ClassLoader]).invoke(builder, classLoader)

        val testCollector = builderClass.getMethod("build").invoke(builder)
        val testCollectorClass =
          classLoader.loadClass("com.github.sbt.junit.jupiter.api.JupiterTestCollector")

        val result = testCollectorClass.getMethod("collectTests").invoke(testCollector)
        val resultClass =
          classLoader.loadClass("com.github.sbt.junit.jupiter.api.JupiterTestCollector$Result")

        val items = resultClass.getMethod(
          "getDiscoveredTests"
        ).invoke(result).asInstanceOf[java.util.List[?]]
        val itemClass =
          classLoader.loadClass("com.github.sbt.junit.jupiter.api.JupiterTestCollector$Item")

        import scala.jdk.CollectionConverters._
        items.asScala.map { item =>
          itemClass.getMethod("getFullyQualifiedClassName").invoke(item).asInstanceOf[String]
        }.toSeq
      }
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

  trait ScalaCheck extends TestModule {
    override def testFramework: T[String] = "org.scalacheck.ScalaCheckFramework"
  }

  def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: Option[Ctx.Env]
  ): Result[(String, Seq[TestResult])] = TestModuleUtil.handleResults(doneMsg, results, ctx)

  def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: Ctx.Env & Ctx.Dest,
      testReportXml: Option[String],
      props: Option[Map[String, String]] = None
  ): Result[(String, Seq[TestResult])] =
    TestModuleUtil.handleResults(doneMsg, results, ctx, testReportXml, props)

  trait JavaModuleBase extends BspModule {
    def ivyDeps: T[Seq[Dep]] = Seq.empty[Dep]
    def mandatoryIvyDeps: T[Seq[Dep]] = Seq.empty[Dep]
    def resources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }
  }

  trait ScalaModuleBase extends mill.Module {
    def scalacOptions: T[Seq[String]] = Seq.empty[String]
  }

}
