package mill.scalalib

import mill.api.{Ctx, PathRef, Result}
import mill.main.client.EnvVars
import mill.testrunner.{Framework, TestArgs, TestResult, TestRunnerUtils}
import mill.util.Jvm
import mill.{Agg, T}
import sbt.testing.Status

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.xml.Elem

private[scalalib] object TestModuleUtil {
  def runTests(
      useArgsFile: Boolean,
      forkArgs: Seq[String],
      selectors: Seq[String],
      scalalibClasspath: Agg[PathRef],
      resources: Seq[PathRef],
      testFramework: String,
      runClasspath: Seq[PathRef],
      testClasspath: Seq[PathRef],
      args: Seq[String],
      testClassLists: Seq[Seq[String]],
      testrunnerEntrypointClasspath: Agg[PathRef],
      forkEnv: Map[String, String],
      testSandboxWorkingDir: Boolean,
      forkWorkingDir: os.Path,
      testReportXml: Option[String]
  )(implicit ctx: mill.api.Ctx) = {

    val (jvmArgs, props: Map[String, String]) = loadArgsAndProps(useArgsFile, forkArgs)

    val testRunnerClasspathArg = scalalibClasspath
      .map(_.path.toNIO.toUri.toURL)
      .mkString(",")

    val resourceEnv = Map(
      EnvVars.MILL_TEST_RESOURCE_DIR -> resources.map(_.path).mkString(";"),
      EnvVars.MILL_WORKSPACE_ROOT -> T.workspace.toString
    )

    def runTestRunnerSubprocess(selectors2: Seq[String], base: os.Path) = {
      val outputPath = base / "out.json"
      val testArgs = TestArgs(
        framework = testFramework,
        classpath = runClasspath.map(_.path),
        arguments = args,
        sysProps = props,
        outputPath = outputPath,
        colored = T.log.colored,
        testCp = testClasspath.map(_.path),
        home = T.home,
        globSelectors = selectors2
      )

      val argsFile = base / "testargs"
      val sandbox = base / "sandbox"
      os.write(argsFile, upickle.default.write(testArgs), createFolders = true)

      os.makeDir.all(sandbox)

      Jvm.runSubprocess(
        mainClass = "mill.testrunner.entrypoint.TestRunnerMain",
        classPath =
          (runClasspath ++ testrunnerEntrypointClasspath).map(
            _.path
          ),
        jvmArgs = jvmArgs,
        envArgs = forkEnv ++ resourceEnv,
        mainArgs = Seq(testRunnerClasspathArg, argsFile.toString),
        workingDir = if (testSandboxWorkingDir) sandbox else forkWorkingDir,
        useCpPassingJar = useArgsFile
      )

      if (!os.exists(outputPath)) Left(s"Test reporting Failed: ${outputPath} does not exist")
      else Right(upickle.default.read[(String, Seq[TestResult])](ujson.read(outputPath.toIO)))
    }

    val globFilter = TestRunnerUtils.globFilter(selectors)

    def doesNotMatchError = Result.Failure(
      s"Test selector does not match any test: ${selectors.mkString(" ")}" +
        "\nRun discoveredTestClasses to see available tests"
    )

    val filteredClassLists0 = testClassLists.map(_.filter(globFilter)).filter(_.nonEmpty)

    val filteredClassLists =
      if (filteredClassLists0.size == 1) filteredClassLists0
      else {
        // If test grouping is enabled and multiple test groups are detected, we need to
        // run test discovery via the test framework's own argument parsing and filtering
        // logic once in memory before we potentially fork off multiple test groups that will
        // each do the same thing and then run tests. This duplication is necessary so we can
        // skip test groups that we know will be empty, which is important because even an empty
        // test group requires spawning a JVM which can take 1+ seconds to realize there are no
        // tests to run and shut down
        val discoveredTests = Jvm.inprocess(
          (runClasspath ++ testrunnerEntrypointClasspath).map(_.path),
          classLoaderOverrideSbtTesting = true,
          isolated = true,
          closeContextClassLoaderWhenDone = false,
          TestRunnerUtils.getTestTasks0(
            Framework.framework(testFramework),
            testClasspath.map(_.path),
            args,
            cls => globFilter(cls.getName),
            _
          )
        ).toSet

        filteredClassLists0.map(_.filter(discoveredTests)).filter(_.nonEmpty)
      }
    if (selectors.nonEmpty && filteredClassLists.isEmpty) throw doesNotMatchError

    val subprocessResult: Either[String, (String, Seq[TestResult])] = filteredClassLists match {
      // When no tests at all are discovered, run at least one test JVM
      // process to go through the test framework setup/teardown logic
      case Nil => runTestRunnerSubprocess(Nil, T.dest)
      case Seq(singleTestClassList) => runTestRunnerSubprocess(singleTestClassList, T.dest)
      case multipleTestClassLists =>
        val maxLength = multipleTestClassLists.length.toString.length
        val futures = multipleTestClassLists.zipWithIndex.map { case (testClassList, i) =>
          val groupPromptMessage = testClassList match {
            case Seq(single) => single
            case multiple =>
              collapseTestClassNames(multiple).mkString(", ") + s", ${multiple.length} suites"
          }

          val paddedIndex = mill.util.Util.leftPad(i.toString, maxLength, '0')
          val folderName = testClassList match {
            case Seq(single) => single
            case multiple =>
              s"group-$paddedIndex-${multiple.head}"
          }

          T.fork.async(T.dest / folderName, paddedIndex, groupPromptMessage) {
            (folderName, runTestRunnerSubprocess(testClassList, T.dest / folderName))
          }
        }

        val outputs = T.fork.awaitAll(futures)

        val (lefts, rights) = outputs.partitionMap {
          case (name, Left(v)) => Left(name + " " + v)
          case (name, Right((msg, results))) => Right((name + " " + msg, results))
        }

        if (lefts.nonEmpty) Left(lefts.mkString("\n"))
        else Right((rights.map(_._1).mkString("\n"), rights.flatMap(_._2)))
    }

    subprocessResult match {
      case Left(errMsg) => Result.Failure(errMsg)
      case Right((doneMsg, results)) =>
        if (results.isEmpty && selectors.nonEmpty) throw doesNotMatchError
        try handleResults(doneMsg, results, T.ctx(), testReportXml)
        catch {
          case e: Throwable => Result.Failure("Test reporting failed: " + e)
        }
    }
  }

  private def loadArgsAndProps(useArgsFile: Boolean, forkArgs: Seq[String]) = {
    if (useArgsFile) {
      val (props, jvmArgs) = forkArgs.partition(_.startsWith("-D"))
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
      forkArgs -> Map()
    }
  }

  @deprecated("Use other overload instead", "Mill after 0.10.2")
  private[scalalib] def handleResults(
      doneMsg: String,
      results: Seq[TestResult]
  ): Result[(String, Seq[TestResult])] = handleResults(doneMsg, results, None)

  private[scalalib] def handleResults(
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

  private[scalalib] def handleResults(
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

  private val FailedTestReportCount = 5
  private val ErrorStatus = Status.Error.name()
  private val FailureStatus = Status.Failure.name()
  private val SkippedStates =
    Set(Status.Ignored.name(), Status.Skipped.name(), Status.Pending.name())

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

  /**
   * Shorten the long list of fully qualified class names by truncating
   * repetitive segments so we can see more stuff on a single line
   */
  def collapseTestClassNames(names0: Seq[String]): Seq[String] = {
    val names = names0.sorted
    Seq(names.head) ++ names.sliding(2).map {
      case Seq(prev, next) =>
        val prevSegments = prev.split('.')
        val nextSegments = next.split('.')

        nextSegments
          .zipWithIndex
          .map { case (s, i) => if (prevSegments.lift(i).contains(s)) s.head else s }
          .mkString(".")
    }
  }
}
