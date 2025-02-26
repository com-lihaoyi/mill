package mill.scalalib

import mill.api.{Ctx, PathRef, Result}
import mill.constants.EnvVars
import mill.testrunner.{TestArgs, TestResult, TestRunnerUtils}
import mill.util.Jvm
import mill.Task
import sbt.testing.Status

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.xml.Elem
import mill.testrunner.TestClusterSignals
import os.SubProcess
import scala.collection.mutable
import scala.util.Random

private[scalalib] object TestModuleUtil {
  def runTests(
      useArgsFile: Boolean,
      forkArgs: Seq[String],
      selectors: Seq[String],
      scalalibClasspath: Seq[PathRef],
      resources: Seq[PathRef],
      testFramework: String,
      runClasspath: Seq[PathRef],
      testClasspath: Seq[PathRef],
      args: Seq[String],
      testClassLists: Seq[Seq[String]],
      testrunnerEntrypointClasspath: Seq[PathRef],
      forkEnv: Map[String, String],
      testSandboxWorkingDir: Boolean,
      forkWorkingDir: os.Path,
      testReportXml: Option[String],
      javaHome: Option[os.Path]
  )(implicit ctx: mill.api.Ctx) = {

    val (jvmArgs, props: Map[String, String]) = loadArgsAndProps(useArgsFile, forkArgs)

    val testRunnerClasspathArg = scalalibClasspath
      .map(_.path.toNIO.toUri.toURL)
      .mkString(",")

    val resourceEnv = Map(
      EnvVars.MILL_TEST_RESOURCE_DIR -> resources.map(_.path).mkString(";"),
      EnvVars.MILL_WORKSPACE_ROOT -> Task.workspace.toString
    )

    val emptyCleanup = () => ()

    def runTestRunnerSubprocess(selectors2: Seq[String], base: os.Path) = {
      val clusterSignals = TestClusterSignals(base, isClusterServer = true)

      val subprocesses = scala.collection.mutable.HashMap.empty[Int, (SubProcess, () => Unit)]

      Runtime.getRuntime().addShutdownHook(Thread(() => {
        var stillHasAliveChild = true
        while (stillHasAliveChild) {
          stillHasAliveChild = false
          subprocesses.filterInPlace { case (_, subprocess -> cleanup) =>
            if (subprocess.isAlive()) {
              stillHasAliveChild = true
              subprocess.destroy()
              cleanup()
            }
            false
          }
        }
        clusterSignals.cleanup()
      }))

      def getProcessBaseFolder(processIndex: Int): os.Path = base / s"$processIndex"

      def spawnProcess(
          processIndex: Int,
          cleanup: () => Unit,
          testCp: Seq[os.Path]
      ): Unit = {
        val processBase = getProcessBaseFolder(processIndex)

        val logger = ctx.log.subLogger(
          processBase / os.up / s"${processBase.last}.log",
          processIndex.toString,
          s"Run tests with process $processIndex"
        )

        val outputPath = processBase / "out.json"

        val testArgs = TestArgs(
          framework = testFramework,
          classpath = runClasspath.map(_.path),
          arguments = args,
          sysProps = props,
          outputPath = outputPath,
          colored = Task.log.colored,
          testCp = testCp,
          globSelectors = selectors2,
          clusterOpt = Some(processIndex -> base)
        )

        val argsFile = processBase / s"testargs"
        val sandbox = processBase / s"sandbox"

        os.write(argsFile, upickle.default.write(testArgs), createFolders = true)
        os.makeDir.all(sandbox)

        val subprocess = logger.withPrompt {
          mill.api.SystemStreams.withStreams(logger.systemStreams) {
            Jvm.spawnProcess(
              mainClass = "mill.testrunner.entrypoint.TestRunnerMain",
              mainArgs = Seq(testRunnerClasspathArg, argsFile.toString),
              javaHome = javaHome,
              jvmArgs = jvmArgs,
              classPath = (runClasspath ++ testrunnerEntrypointClasspath).map(_.path),
              cpPassingJarPath = Option.when(useArgsFile)(
                os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)
              ),
              env = forkEnv ++ resourceEnv,
              cwd = if (testSandboxWorkingDir) sandbox else forkWorkingDir,
              stdin = os.Inherit,
              stdout = os.Inherit
            )
          }
        }

        subprocesses.update(processIndex, subprocess -> cleanup)
      }

      {
        val processIndex = subprocesses.size
        clusterSignals.createStealingFile(processIndex)
        spawnProcess(
          processIndex,
          () => clusterSignals.removeStealingFile(processIndex),
          testClasspath.map(_.path)
        )
      }

      var stop = false
      val running = mutable.ArrayBuffer.empty[Int]
      val blocking = mutable.ArrayBuffer.empty[Int]
      val stealing = mutable.ArrayBuffer.empty[Int]

      while (!stop) {
        stop = true
        running.clear()
        blocking.clear()
        stealing.clear()
        val totalProcessCount = subprocesses.size
        val offset = Random.nextInt(totalProcessCount)
        Range(0, totalProcessCount).foreach { index =>
          val processIndex = (index + offset) % totalProcessCount
          subprocesses.updateWith(processIndex) {
            case Some(subprocess -> cleanup) =>
              if (subprocess.isAlive()) {
                stop = false
                clusterSignals.getClusterState(processIndex) match {
                  case TestClusterSignals.ClusterState.Running => running.append(processIndex)
                  case TestClusterSignals.ClusterState.Blocking => blocking.append(processIndex)
                  case TestClusterSignals.ClusterState.Stealing(_) => ()
                  case TestClusterSignals.ClusterState.RequestingStealPermission =>
                    stealing.append(processIndex)
                  case TestClusterSignals.ClusterState.StealPermissionApproved(_) => ()
                  case TestClusterSignals.ClusterState.StealPermissionDenied => ()
                  case TestClusterSignals.ClusterState.Stop => ()
                  case TestClusterSignals.ClusterState.Unrecognize => ()
                }
                Some(subprocess -> cleanup)
              } else {
                cleanup()
                Some(subprocess -> emptyCleanup)
              }
            case None => None
          }
        }
        while (stealing.nonEmpty && (blocking.nonEmpty || running.nonEmpty)) {
          val victim = if (blocking.nonEmpty) blocking.remove(0) else running.remove(0)
          val stealer = stealing.remove(0)
          clusterSignals.writeClusterState(
            stealer,
            TestClusterSignals.ClusterState.StealPermissionApproved(victim)
          )
        }
        while (stealing.nonEmpty) {
          val stealer = stealing.remove(0)
          clusterSignals.writeClusterState(
            stealer,
            TestClusterSignals.ClusterState.StealPermissionDenied
          )
        }
        if (!stop) {
          if (
            blocking.nonEmpty && stealing.isEmpty && (subprocesses.size < TestClusterSignals.MaxTestProcessCount)
          ) {
            Task.fork.tryTakeAsyncSlot().foreach { forkCleanup =>
              val processIndex = subprocesses.size
              clusterSignals.createStealingFile(processIndex)
              spawnProcess(
                processIndex,
                () => {
                  clusterSignals.removeStealingFile(processIndex)
                  forkCleanup.close()
                },
                Seq.empty
              )
            }
          }
        }
      }

      val (lefts, rights) = subprocesses.map { case (processIndex, _) =>
        val output = getProcessBaseFolder(processIndex) / "out.json"
        if (!os.exists(output)) {
          Left(processIndex)
        } else {
          val (doneMsg, results) =
            upickle.default.read[(String, Seq[TestResult])](ujson.read(output.toIO))
          Right(doneMsg -> results)
        }
      }.toSeq.partitionMap(identity)

      val outputPath = base / "out.json"

      val result = if (lefts.nonEmpty) {
        Result.Failure(s"Test reporting Failed: ${outputPath} does not exist")
      } else {
        val doneMsgs = rights.map(_._1).mkString("\n")
        val results = rights.flatMap(_._2)
        os.write(outputPath, upickle.default.write((doneMsgs, results)))
        Result.Success((doneMsgs, results))
      }

      clusterSignals.cleanup()
      result
    }

    val globFilter = TestRunnerUtils.globFilter(selectors)

    def doesNotMatchError = new Result.Exception(
      s"Test selector does not match any test: ${selectors.mkString(" ")}" +
        "\nRun discoveredTestClasses to see available tests"
    )

    val filteredClassLists0 = testClassLists.map(_.filter(globFilter)).filter(_.nonEmpty)

    val filteredClassLists =
      if (filteredClassLists0.size == 1) filteredClassLists0
      else {
        // If test grouping is enabled and multiple test groups are detected, we need to
        // run test discovery via the test framework's own argument parsing and filtering
        // logic once before we potentially fork off multiple test groups that will
        // each do the same thing and then run tests. This duplication is necessary so we can
        // skip test groups that we know will be empty, which is important because even an empty
        // test group requires spawning a JVM which can take 1+ seconds to realize there are no
        // tests to run and shut down

        val discoveredTests = if (javaHome.isDefined) {
          Jvm.callProcess(
            mainClass = "mill.testrunner.GetTestTasksMain",
            classPath = scalalibClasspath.map(_.path).toVector,
            mainArgs =
              (runClasspath ++ testrunnerEntrypointClasspath).flatMap(p =>
                Seq("--runCp", p.path.toString)
              ) ++
                testClasspath.flatMap(p => Seq("--testCp", p.path.toString)) ++
                Seq("--framework", testFramework) ++
                selectors.flatMap(s => Seq("--selectors", s)) ++
                args.flatMap(s => Seq("--args", s)),
            javaHome = javaHome,
            stdin = os.Inherit,
            stdout = os.Pipe,
            cwd = Task.dest
          ).out.lines().toSet
        } else {
          mill.testrunner.GetTestTasksMain.main0(
            (runClasspath ++ testrunnerEntrypointClasspath).map(_.path),
            testClasspath.map(_.path),
            testFramework,
            selectors,
            args
          ).toSet
        }

        filteredClassLists0.map(_.filter(discoveredTests)).filter(_.nonEmpty)
      }
    if (selectors.nonEmpty && filteredClassLists.isEmpty) throw doesNotMatchError

    val subprocessResult: Result[(String, Seq[TestResult])] = filteredClassLists match {
      // When no tests at all are discovered, run at least one test JVM
      // process to go through the test framework setup/teardown logic
      case Nil => runTestRunnerSubprocess(Nil, Task.dest)
      case Seq(singleTestClassList) => runTestRunnerSubprocess(singleTestClassList, Task.dest)
      case multipleTestClassLists =>
        val maxLength = multipleTestClassLists.length.toString.length
        val futures = multipleTestClassLists.zipWithIndex.map { case (testClassList, i) =>
          val groupPromptMessage = testClassList match {
            case Seq(single) => single
            case multiple =>
              collapseTestClassNames(multiple).mkString(", ") + s", ${multiple.length} suites"
          }

          val paddedIndex = mill.internal.Util.leftPad(i.toString, maxLength, '0')
          val folderName = testClassList match {
            case Seq(single) => single
            case multiple =>
              s"group-$paddedIndex-${multiple.head}"
          }

          Task.fork.async(Task.dest / folderName, paddedIndex, groupPromptMessage) {
            (folderName, runTestRunnerSubprocess(testClassList, Task.dest / folderName))
          }
        }

        val outputs = Task.fork.awaitAll(futures)

        val (lefts, rights) = outputs.partitionMap {
          case (name, Result.Failure(v)) => Left(name + " " + v)
          case (name, Result.Success((msg, results))) => Right((name + " " + msg, results))
        }

        if (lefts.nonEmpty) Result.Failure(lefts.mkString("\n"))
        else Result.Success((rights.map(_._1).mkString("\n"), rights.flatMap(_._2)))
    }

    subprocessResult match {
      case Result.Failure(errMsg) => Result.Failure(errMsg)
      case Result.Success((doneMsg, results)) =>
        if (results.isEmpty && selectors.nonEmpty) throw doesNotMatchError
        try handleResults(doneMsg, results, Task.ctx(), testReportXml)
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

      Result.Failure(msg)
    }
  }

  private[scalalib] def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: Ctx.Env & Ctx.Dest,
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
