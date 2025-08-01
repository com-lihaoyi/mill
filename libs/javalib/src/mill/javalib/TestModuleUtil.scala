package mill.javalib

import mill.api.{PathRef, TaskCtx}
import mill.api.Result
import mill.api.daemon.internal.TestReporter
import mill.util.Jvm
import mill.api.internal.Util
import mill.Task
import sbt.testing.Status

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.xml.Elem
import scala.collection.mutable
import mill.api.Logger

import java.util.concurrent.ConcurrentHashMap
import mill.api.BuildCtx
import mill.javalib.testrunner.{GetTestTasksMain, TestArgs, TestResult, TestRunnerUtils}
import os.Path

import scala.concurrent.Future

/**
 * Implementation code used by [[TestModule]] to actually run tests.
 */
@mill.api.daemon.experimental
final class TestModuleUtil(
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
    javaHome: Option[os.Path],
    testParallelism: Boolean,
    testLogLevel: TestReporter.LogLevel,
    propagateEnv: Boolean = true
)(implicit ctx: mill.api.TaskCtx) {

  private val (jvmArgs, props) = TestModuleUtil.loadArgsAndProps(useArgsFile, forkArgs)

  private val testRunnerClasspathArg = scalalibClasspath
    .map(_.path.toURL)
    .mkString(",")

  def runTests(): Result[(msg: String, results: Seq[TestResult])] = {
    val globFilter = TestRunnerUtils.globFilter(selectors)

    def doesNotMatchError = new Result.Exception(
      s"Test selector does not match any test: ${selectors.mkString(" ")}" +
        "\nRun discoveredTestClasses to see available tests"
    )

    /** This is filtered by mill. */
    val filteredClassLists0 = testClassLists.map(_.filter(globFilter)).filter(_.nonEmpty)

    /** This is filtered by the test framework. */
    val filteredClassLists =
      if (filteredClassLists0.size == 1 && !testParallelism) filteredClassLists0
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
            mainClass = "mill.javalib.testrunner.GetTestTasksMain",
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
          GetTestTasksMain.main0(
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

    val result = runTestQueueScheduler(filteredClassLists)

    result match {
      case Result.Failure(errMsg) => Result.Failure(errMsg)
      case Result.Success((doneMsg, results)) =>
        if (results.isEmpty && selectors.nonEmpty) throw doesNotMatchError
        try TestModuleUtil.handleResults(doneMsg, results, Task.ctx(), testReportXml)
        catch {
          case e: Throwable => Result.Failure("Test reporting failed: " + e)
        }
    }
  }

  def callTestRunnerSubprocess(
      baseFolder: os.Path,
      resultPath: os.Path,
      // either:
      // - Left(selectors):
      //     - list of glob selectors to feed to the test runner directly.
      // - Right((startingTestClass, testClassQueueFolder, claimFolder)):
      //     - first test class to run, folder containing test classes for test runner to claim from, and the worker's base folder.
      selector: Either[Seq[String], (Option[String], os.Path, os.Path)],
      poll: () => Unit
  )(implicit ctx: mill.api.TaskCtx) = {
    if (!os.exists(baseFolder)) os.makeDir.all(baseFolder)

    val outputPath = baseFolder / "out.json"

    val testArgs = TestArgs(
      framework = testFramework,
      classpath = runClasspath.map(_.path),
      arguments = args,
      sysProps = props,
      outputPath = outputPath,
      resultPath = resultPath,
      colored = Task.log.prompt.colored,
      testCp = testClasspath.map(_.path),
      globSelectors = selector,
      logLevel = testLogLevel
    )

    val argsFile = baseFolder / "testargs"
    val sandbox = baseFolder / "sandbox"
    os.write(argsFile, upickle.default.write(testArgs), createFolders = true)

    os.makeDir.all(sandbox)

    val proc = BuildCtx.withFilesystemCheckerDisabled {
      Jvm.spawnProcess(
        mainClass = "mill.javalib.testrunner.entrypoint.TestRunnerMain",
        classPath = (runClasspath ++ testrunnerEntrypointClasspath).map(_.path),
        jvmArgs = jvmArgs,
        env = (if (propagateEnv) Task.env else Map()) ++ forkEnv,
        mainArgs = Seq(testRunnerClasspathArg, argsFile.toString),
        cwd = if (testSandboxWorkingDir) sandbox else forkWorkingDir,
        cpPassingJarPath = Option.when(useArgsFile)(
          os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)
        ),
        javaHome = javaHome,
        stdin = os.Inherit,
        stdout = os.Inherit,
        propagateEnv = false
      )
    }
    while (proc.isAlive()) {
      Thread.sleep(10)
      poll()
    }

    if (!os.exists(outputPath))
      Result.Failure(s"Test reporting Failed: ${outputPath} does not exist")
    else
      Result.Success(upickle.default.read[(String, Seq[TestResult])](ujson.read(outputPath.toIO)))
  }

  def prepareTestClassesFolder(selectors2: Seq[String], base: os.Path): os.Path = {
    // test-classes folder is used to store the test classes for the children test runners to claim from
    val testClassQueueFolder = base / "test-classes"
    os.makeDir.all(testClassQueueFolder)
    selectors2.zipWithIndex.foreach { case (s, _) =>
      os.write.over(testClassQueueFolder / s, Array.empty[Byte])
    }
    testClassQueueFolder
  }

  def jobsProcessLength(numTests: Int) = {
    val cappedJobs = Math.max(Math.min(Task.ctx().jobs, numTests), 1)
    (cappedJobs, cappedJobs.toString.length)
  }

  def runTestQueueScheduler(
      filteredClassLists: Seq[Seq[String]]
  )(implicit ctx: mill.api.TaskCtx) = {

    val filteredClassCount: Int = filteredClassLists.map(_.size).sum

    val groupFolderData: Seq[(Path, Path, Int)] = prepareTestGroups(filteredClassLists)

    val outputs = {
      // We got "--jobs" threads, and "groupLength" test groups, so we will spawn at most jobs * groupLength runners here
      // In most case, this is more than necessary, and runner creation is expensive,
      // but we have a check for non-empty test-classes folder before really spawning a new runner, so in practice the overhead is low
      val subprocessFutures = for {
        ((groupFolder, testClassQueueFolder, numTests), groupIndex) <-
          groupFolderData.zipWithIndex.toVector
        (jobs, maxProcessLength) = jobsProcessLength(numTests)
        paddedGroupIndex = Util.leftPad(
          groupIndex.toString,
          groupFolderData.length.toString.length,
          '0'
        )
        processIndex <- 0 until Math.max(Math.min(jobs, numTests), 1)
      } yield runTestFuture(
        filteredClassCount,
        groupFolderData,
        groupFolder,
        testClassQueueFolder,
        groupFolder.last,
        maxProcessLength,
        paddedGroupIndex,
        processIndex
      )

      Task.fork.blocking {
        TestModuleUtil.waitForFutures(ctx, filteredClassCount, subprocessFutures)
      }

      subprocessFutures.flatMap(_._2.value).map(_.get)
    }

    TestModuleUtil.processTestResults(outputs)
  }

  private def prepareTestGroups(filteredClassLists: Seq[Seq[String]]) = {
    filteredClassLists match {
      case Nil => Seq((Task.dest, prepareTestClassesFolder(Nil, Task.dest), 0))
      case Seq(singleTestClassList) =>
        Seq((
          Task.dest,
          prepareTestClassesFolder(singleTestClassList, Task.dest),
          singleTestClassList.length
        ))
      case multipleTestClassLists =>
        val maxLength = multipleTestClassLists.length.toString.length
        multipleTestClassLists.zipWithIndex.map { case (testClassList, i) =>
          val paddedIndex = mill.api.internal.Util.leftPad(i.toString, maxLength, '0')
          val folderName = testClassList match {
            case Seq(single) => single
            case multiple =>
              s"group-$paddedIndex-${multiple.head}"
          }

          (
            Task.dest / folderName,
            prepareTestClassesFolder(testClassList, Task.dest / folderName),
            testClassList.length
          )
        }
    }
  }

  def runTestFuture(
      filteredClassCount: Int,
      groupFolderData: Seq[(Path, Path, Int)],
      groupFolder: Path,
      testClassQueueFolder: Path,
      groupName: String,
      maxProcessLength: Int,
      paddedGroupIndex: String,
      processIndex: Int
  ) = {
    val paddedProcessIndex =
      mill.api.internal.Util.leftPad(processIndex.toString, maxProcessLength, '0')

    val workerLabel = s"worker-$paddedProcessIndex"
    val processFolder =
      if (testParallelism && filteredClassCount != 1) groupFolder / workerLabel
      else groupFolder

    val resultPath = processFolder / s"result.log"
    os.write.over(resultPath, upickle.default.write((0L, 0L)), createFolders = true)
    val label =
      if (groupFolderData.size == 1) paddedProcessIndex
      else s"$paddedGroupIndex-$paddedProcessIndex"

    def fork[T](block: Logger => T): Future[T] = {
      if (testParallelism && filteredClassCount != 1) {
        Task.fork.async(
          processFolder,
          label,
          "",
          // With the test queue scheduler, prioritize the *first* test subprocess
          // over other Mill tasks via `priority = -1`, but de-prioritize the others
          // increasingly according to their processIndex. This should help Mill
          // use fewer longer-lived test subprocesses, minimizing JVM startup overhead
          priority = if (processIndex == 0) -1 else processIndex
        ) {
          block
        }
      } else Future.successful(block(ctx.log))
    }

    processFolder -> fork {
      logger =>
        val testClassTimeMap = new ConcurrentHashMap[String, Long]()

        val claimFolder = processFolder / "claim"
        os.makeDir.all(claimFolder)

        // Make sure we can claim at least one test class to start before we spawn the
        // subprocess, because creating JVM subprocesses are expensive and we don't want
        // to spawn one if there is nothing for it to do
        val startingTestClass = os
          .list
          .stream(testClassQueueFolder)
          .map(TestRunnerUtils.claimFile(_, claimFolder))
          .collectFirst { case Some(name) => name }

        // force run when processIndex == 0 (first subprocess), even if there are no tests to run
        // to force the process to go through the test framework setup/teardown logic
        val result = Option.when(processIndex == 0 || startingTestClass.nonEmpty) {
          startingTestClass.foreach(logger.ticker(_))
          // queue.log file will be appended by the runner with the stolen test class's name
          // it can be used to check the order of test classes of the runner
          val claimLog = processFolder / "claim.log"
          os.write.over(claimLog, Array.empty[Byte])

          var seenLines = 0
          callTestRunnerSubprocess(
            processFolder,
            processFolder / "result.log",
            Right((startingTestClass, testClassQueueFolder, claimFolder)),
            () => {
              val lines = os.read.lines(claimLog)
              lines.drop(seenLines).collect {
                case s"CLAIM $currentTestClass $nanoTime" =>
                  logger.prompt.logBeginChromeProfileEntry(currentTestClass, nanoTime.toLong)
                case s"COMPLETED $nanoTime" =>
                  logger.prompt.logEndChromeProfileEntry(nanoTime.toLong)
              }
              seenLines = lines.length
              val now = System.currentTimeMillis()
              lines.collect { case s"CLAIM $currentTestClass $_" =>
                testClassTimeMap.putIfAbsent(currentTestClass, now)
                val last = testClassTimeMap.get(currentTestClass)
                logger.ticker(s"$currentTestClass${Util.renderSecondsSuffix(now - last)}")
              }
            }
          )
        }

        val claimedClasses = if (os.exists(claimFolder)) os.list(claimFolder).size else 0

        (claimedClasses, groupName, result)
    }
  }
}

private[mill] object TestModuleUtil {

  def loadArgsAndProps(
      useArgsFile: Boolean,
      forkArgs: Seq[String]
  ): (Seq[String], Map[String, String]) = {
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

  private[mill] def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: Option[TaskCtx.Env]
  ): Result[(msg: String, results: Seq[TestResult])] = {

    val badTests: Seq[TestResult] =
      results.filter(x => Set("Error", "Failure").contains(x.status))
    if (badTests.isEmpty) {
      Result.Success((msg = doneMsg, results = results))
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

  private[mill] def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: TaskCtx.Env & TaskCtx.Dest,
      testReportXml: Option[String],
      props: Option[Map[String, String]] = None
  ): Result[(msg: String, results: Seq[TestResult])] = {
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

  private[mill] def genTestXmlReport(
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

  def formatTimestamp(timestamp: Instant): String = {
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
      LocalDateTime.ofInstant(
        timestamp.truncatedTo(ChronoUnit.SECONDS),
        ZoneId.of("UTC")
      )
    )
  }

  def testCaseStatus(e: TestResult): Option[Elem] = {
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

  def processTestResults(outputs: Vector[(
      Int,
      String,
      Option[Result[(String, Seq[TestResult])]]
  )]) = {
    val failMap = mutable.Map.empty[String, String]
    val successMap = mutable.Map.empty[String, (String, Seq[TestResult])]
    val subprocessResult = {

      outputs.foreach {
        case (_, name, Some(Result.Failure(v))) =>
          failMap.updateWith(name) {
            case Some(old) => Some(old + " " + v)
            case None => Some(v)
          }
        case (_, name, Some(Result.Success((msg, results)))) =>
          successMap.updateWith(name) {
            case Some((oldMsg, oldResults)) => Some((oldMsg + " " + msg, oldResults ++ results))
            case None => Some((msg, results))
          }
        case _ => ()
      }

      if (failMap.nonEmpty) {
        Result.Failure(failMap.values.mkString("\n"))
      } else {
        Result.Success((
          successMap.values.map(_._1).mkString("\n"),
          successMap.values.flatMap(_._2).toSeq
        ))
      }
    }

    subprocessResult
  }

  private def waitForFutures(
      ctx: TaskCtx,
      filteredClassCount: Int,
      subprocessFutures: Vector[(
          Path,
          Future[(Int, String, Option[Result[(String, Seq[TestResult])]])]
      )]
  ): Unit = {
    while ({
      val claimedCounts = subprocessFutures.flatMap(_._2.value).flatMap(_.toOption).map(_._1)
      !(
        (claimedCounts.sum == filteredClassCount && subprocessFutures.head._2.isCompleted) ||
          subprocessFutures.forall(_._2.isCompleted)
      )
    }) {
      val allResultsOpt =
        // Don't crash if a result.log file is malformed, just try again
        // since that might happen transiently during a write
        try {
          Some(subprocessFutures.map(_._1 / "result.log").map(p =>
            upickle.default.read[(Long, Long)](os.read.stream(p))
          ))
        } catch {
          case _: Exception => None
        }

      for (allResults <- allResultsOpt) {
        val totalSuccess = allResults.map(_._1).sum
        val totalFailure = allResults.map(_._2).sum
        val totalClassCount = filteredClassCount
        val failureSuffix = if totalFailure > 0 then s", $totalFailure failures" else ""
        ctx.log.ticker(s"${totalSuccess + totalFailure}/$totalClassCount completed$failureSuffix.")
      }
      Thread.sleep(10)
    }
  }
}
