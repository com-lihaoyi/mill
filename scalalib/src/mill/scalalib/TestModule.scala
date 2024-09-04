package mill.scalalib

import mill.api.{Ctx, PathRef, Result}
import mill.main.client.EnvVars
import mill.define.{Command, Task, TaskModule}
import mill.scalalib.bsp.{BspBuildTarget, BspModule}
import mill.testrunner.{Framework, TestArgs, TestResult, TestRunner, TestRunnerUtils}
import mill.util.Jvm
import mill.{Agg, T}
import sbt.testing.Status

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.xml.Elem

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
  def testClasspath: T[Seq[PathRef]] = T { localRunClasspath() }

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

  def discoveredTestClasses: T[Seq[String]] = T {
    val classes = Jvm.inprocess(
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
    classes.sorted
  }

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * @see [[testCached]]
   */
  def test(args: String*): Command[(String, Seq[TestResult])] =
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
  def testCached: T[(String, Seq[TestResult])] = T {
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
  def testOnly(args: String*): Command[(String, Seq[TestResult])] = {
    val (selector, testArgs) = args.indexOf("--") match {
      case -1 => (args, Seq.empty)
      case pos =>
        val (s, t) = args.splitAt(pos)
        (s, t.tail)
    }
    T.command {
      testTask(T.task { testArgs }, T.task { selector })()
    }
  }

  /**
   * Controls whether the TestRunner should receive it's arguments via an args-file instead of a as long parameter list.
   * Defaults to what `runUseArgsFile` return.
   */
  def testUseArgsFile: T[Boolean] = T { runUseArgsFile() || scala.util.Properties.isWin }

  /**
   * Sets the file name for the generated JUnit-compatible test report.
   * If None is set, no file will be generated.
   */
  def testReportXml: T[Option[String]] = T(Some("test-report.xml"))

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

      val selectors = globSelectors()

      val testArgs = TestArgs(
        framework = testFramework(),
        classpath = runClasspath().map(_.path),
        arguments = args(),
        sysProps = props,
        outputPath = outputPath,
        colored = T.log.colored,
        testCp = testClasspath().map(_.path),
        home = T.home,
        globSelectors = selectors
      )

      val testRunnerClasspathArg = zincWorker().scalalibClasspath()
        .map(_.path.toNIO.toUri.toURL)
        .mkString(",")

      val argsFile = T.dest / "testargs"
      os.write(argsFile, upickle.default.write(testArgs))
      val mainArgs = Seq(testRunnerClasspathArg, argsFile.toString)

      os.makeDir(T.dest / "sandbox")
      val resourceEnv =
        Map(EnvVars.MILL_TEST_RESOURCE_FOLDER -> resources().map(_.path).mkString(";"))
      Jvm.runSubprocess(
        mainClass = "mill.testrunner.entrypoint.TestRunnerMain",
        classPath =
          (runClasspath() ++ zincWorker().testrunnerEntrypointClasspath()).map(
            _.path
          ),
        jvmArgs = jvmArgs,
        envArgs = forkEnv() ++ resourceEnv,
        mainArgs = mainArgs,
        workingDir = if (testSandboxWorkingDir()) T.dest / "sandbox" else forkWorkingDir(),
        useCpPassingJar = useArgsFile
      )

      if (!os.exists(outputPath)) Result.Failure("Test execution failed.")
      else
        try {
          val jsonOutput = ujson.read(outputPath.toIO)
          val (doneMsg, results) = {
            upickle.default.read[(String, Seq[TestResult])](jsonOutput)
          }
          if (results.isEmpty && selectors.nonEmpty) {
            // no tests ran but we expected some to run, as we applied a filter (e.g. via `testOnly`)
            Result.Failure(
              s"Test selector does not match any test: ${selectors.mkString(" ")}" + "\nRun discoveredTestClasses to see available tests"
            )
          } else TestModule.handleResults(doneMsg, results, T.ctx(), testReportXml())
        } catch {
          case e: Throwable =>
            Result.Failure("Test reporting failed: " + e)
        }
    }

  /**
   * Discovers and runs the module's tests in-process in an isolated classloader,
   * reporting the results to the console
   */
  def testLocal(args: String*): Command[(String, Seq[TestResult])] = T.command {
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
  private val FailedTestReportCount = 5
  private val ErrorStatus = Status.Error.name()
  private val FailureStatus = Status.Failure.name()
  private val SkippedStates =
    Set(Status.Ignored.name(), Status.Skipped.name(), Status.Pending.name())

  /**
   * TestModule using TestNG Framework to run tests.
   * You need to provide the testng dependency yourself.
   */
  trait TestNg extends TestModule {
    override def testFramework: T[String] = "mill.testng.TestNGFramework"
    override def ivyDeps: T[Agg[Dep]] = T {
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
    override def ivyDeps: T[Agg[Dep]] = T {
      super.ivyDeps() ++ Agg(ivy"${mill.scalalib.api.Versions.sbtTestInterface}")
    }
  }

  /**
   * TestModule that uses JUnit 5 Framework to run tests.
   * You may want to provide the junit dependency explicitly to use another version.
   */
  trait Junit5 extends TestModule {
    override def testFramework: T[String] = "com.github.sbt.junit.jupiter.api.JupiterFramework"
    override def ivyDeps: T[Agg[Dep]] = T {
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
  ): Result[(String, Seq[TestResult])] = {

    val badTests: Seq[TestResult] =
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

  def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: Ctx.Env with Ctx.Dest,
      testReportXml: Option[String],
      props: Option[Map[String, String]] = None
  ): Result[(String, Seq[TestResult])] = {
    for {
      fileName <- testReportXml
      path = ctx.dest / fileName
      xml <- genTestXmlReport(results, Instant.now(), props.getOrElse(Map.empty))
      _ = scala.xml.XML.save(path.toString(), xml, xmlDecl = true)
    } yield ()
    handleResults(doneMsg, results, Some(ctx))
  }

  trait JavaModuleBase extends BspModule {
    def ivyDeps: T[Agg[Dep]] = Agg.empty[Dep]
    def resources: T[Seq[PathRef]] = T { Seq.empty[PathRef] }
  }

  trait ScalaModuleBase extends mill.Module {
    def scalacOptions: T[Seq[String]] = Seq.empty[String]
  }

  private[scalalib] def genTestXmlReport(
      results0: Seq[TestResult],
      timestamp: Instant,
      props: Map[String, String]
  ): Option[Elem] = {
    def durationAsString(value: Long) = (value / 1000d).toString
    def testcaseName(testResult: TestResult) =
      testResult.selector.replace(s"${testResult.fullyQualifiedName}.", "")

    def properties: Elem = {
      val ps = props.map { case (key, value) =>
        <property name={key} value={value}/>
      }
      <properties>
        {ps}
      </properties>
    }

    val suites = results0.groupBy(_.fullyQualifiedName).map { case (fqn, testResults) =>
      val cases = testResults.map { testResult =>
        val testName = testcaseName(testResult)
        <testcase classname={testResult.fullyQualifiedName}
                  name={testName}
                  time={durationAsString(testResult.duration)}>
          {testCaseStatus(testResult).orNull}
        </testcase>
      }

      <testsuite name={fqn}
                 tests={testResults.length.toString}
                 failures={testResults.count(_.status == FailureStatus).toString}
                 errors={testResults.count(_.status == ErrorStatus).toString}
                 skipped={
        testResults.count(testResult => SkippedStates.contains(testResult.status)).toString
      }
                 time={durationAsString(testResults.map(_.duration).sum)}
                 timestamp={formatTimestamp(timestamp)}>
        {properties}
        {cases}
      </testsuite>
    }
    // todo add the parent module name
    val xml =
      <testsuites tests={results0.size.toString}
                  failures={results0.count(_.status == FailureStatus).toString}
                  errors={results0.count(_.status == ErrorStatus).toString}
                  skipped={
        results0.count(testResult => SkippedStates.contains(testResult.status)).toString
      }
                  time={durationAsString(results0.map(_.duration).sum)}>
        {suites}
      </testsuites>
    if (results0.nonEmpty) Some(xml) else None
  }

  private def formatTimestamp(timestamp: Instant): String = {
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
      LocalDateTime.ofInstant(
        timestamp.truncatedTo(ChronoUnit.SECONDS),
        ZoneId.of("UTC")
      )
    )
  }

  private def testCaseStatus(e: TestResult): Option[Elem] = {
    val trace: String = e.exceptionTrace.map(stackTraceTrace =>
      stackTraceTrace.map(t =>
        s"${t.getClassName}.${t.getMethodName}(${t.getFileName}:${t.getLineNumber})"
      )
        .mkString(
          s"${e.exceptionName.getOrElse("")}: ${e.exceptionMsg.getOrElse("")}\n    at ",
          "\n    at ",
          ""
        )
    ).getOrElse("")
    e.status match {
      case ErrorStatus if (e.exceptionMsg.isDefined && e.exceptionName.isDefined) =>
        Some(<error message={e.exceptionMsg.get} type={e.exceptionName.get}>
          {trace}
        </error>)
      case ErrorStatus => Some(<error message="No Exception or message provided"/>)
      case FailureStatus if (e.exceptionMsg.isDefined && e.exceptionName.isDefined) =>
        Some(<failure message={e.exceptionMsg.get} type={e.exceptionName.get}>
          {trace}
        </failure>)
      case FailureStatus => Some(<failure message="No Exception or message provided"/>)
      case s if SkippedStates.contains(s) => Some(<skipped/>)
      case _ => None
    }
  }
}
