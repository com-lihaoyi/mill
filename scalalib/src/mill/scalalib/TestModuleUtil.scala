package mill.scalalib

import mill.api.{Ctx, PathRef, Result}
import mill.constants.EnvVars
import mill.runner.api.TestReporter
import mill.testrunner.{TestArgs, TestResult, TestRunnerUtils}
import mill.util.Jvm
import mill.Task
import sbt.testing.Status

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.xml.Elem
import scala.collection.mutable
import mill.api.Logger
import java.util.concurrent.Executors

private final class TestModuleUtil(
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
    testLogLevel: TestReporter.LogLevel
)(implicit ctx: mill.api.Ctx) {

  private val (jvmArgs, props: Map[String, String]) =
    TestModuleUtil.loadArgsAndProps(useArgsFile, forkArgs)

  private val testRunnerClasspathArg = scalalibClasspath
    .map(_.path.toNIO.toUri.toURL)
    .mkString(",")

  private val resourceEnv = Map(
    EnvVars.MILL_TEST_RESOURCE_DIR -> resources.map(_.path).mkString(";"),
    EnvVars.MILL_WORKSPACE_ROOT -> Task.workspace.toString
  )

  def runTests(): Result[(String, Seq[TestResult])] = {
    val globFilter = TestRunnerUtils.globFilter(selectors)

    def doesNotMatchError = new Result.Exception(
      s"Test selector does not match any test: ${selectors.mkString(" ")}" +
        "\nRun discoveredTestClasses to see available tests"
    )

    val filteredClassLists0 = testClassLists.map(_.filter(globFilter)).filter(_.nonEmpty)

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

    val result = if (testParallelism) {
      runTestQueueScheduler(filteredClassLists)
    } else {
      runTestDefault(filteredClassLists)
    }

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

  private def callTestRunnerSubprocess(
      baseFolder: os.Path,
      resultPath: os.Path,
      // either:
      // - Left(selectors):
      //     - list of glob selectors to feed to the test runner directly.
      // - Right((startingTestClass, testClassQueueFolder, claimFolder)):
      //     - first test class to run, folder containing test classes for test runner to claim from, and the worker's base folder.
      selector: Either[Seq[String], (Option[String], os.Path, os.Path)]
  )(implicit ctx: mill.api.Ctx) = {
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

    os.checker.withValue(os.Checker.Nop) {
      Jvm.callProcess(
        mainClass = "mill.testrunner.entrypoint.TestRunnerMain",
        classPath = (runClasspath ++ testrunnerEntrypointClasspath).map(_.path),
        jvmArgs = jvmArgs,
        env = forkEnv ++ resourceEnv,
        mainArgs = Seq(testRunnerClasspathArg, argsFile.toString),
        cwd = if (testSandboxWorkingDir) sandbox else forkWorkingDir,
        cpPassingJarPath = Option.when(useArgsFile)(
          os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)
        ),
        javaHome = javaHome,
        stdin = os.Inherit,
        stdout = os.Inherit
      )
    }

    if (!os.exists(outputPath))
      Result.Failure(s"Test reporting Failed: ${outputPath} does not exist")
    else
      Result.Success(upickle.default.read[(String, Seq[TestResult])](ujson.read(outputPath.toIO)))
  }

  private def runTestDefault(
      filteredClassLists: Seq[Seq[String]]
  )(implicit ctx: mill.api.Ctx) = {

    def runTestRunnerSubprocess(
        base: os.Path,
        testClassList: Seq[String],
        workerResultSet: java.util.concurrent.ConcurrentMap[os.Path, Unit]
    ) = {
      os.makeDir.all(base)

      // test runner will log success/failure test class counter here while running
      val resultPath = base / s"result.log"
      os.write.over(resultPath, upickle.default.write((0L, 0L)))
      workerResultSet.put(resultPath, ())

      callTestRunnerSubprocess(
        base,
        resultPath,
        Left(testClassList)
      )
    }

    TestModuleUtil.withTestProgressTickerThread(filteredClassLists.map(_.size).sum) {
      (_, workerResultSet) =>
        filteredClassLists match {
          // When no tests at all are discovered, run at least one test JVM
          // process to go through the test framework setup/teardown logic
          case Nil => runTestRunnerSubprocess(Task.dest, Nil, workerResultSet)
          case Seq(singleTestClassList) =>
            runTestRunnerSubprocess(Task.dest, singleTestClassList, workerResultSet)
          case multipleTestClassLists =>
            val maxLength = multipleTestClassLists.length.toString.length
            val futures = multipleTestClassLists.zipWithIndex.map { case (testClassList, i) =>
              val groupPromptMessage = testClassList match {
                case Seq(single) => single
                case multiple =>
                  TestModuleUtil.collapseTestClassNames(
                    multiple
                  ).mkString(", ") + s", ${multiple.length} suites"
              }

              val paddedIndex = mill.internal.Util.leftPad(i.toString, maxLength, '0')
              val folderName = testClassList match {
                case Seq(single) => single
                case multiple =>
                  s"group-$paddedIndex-${multiple.head}"
              }

              // set priority = -1 to always prioritize test subprocesses over normal Mill
              // tasks. This minimizes the number of blocked tasks since Mill tasks can be
              // blocked on test subprocesses, but not vice versa, so better to schedule
              // the test subprocesses first
              Task.fork.async(
                Task.dest / folderName,
                paddedIndex,
                groupPromptMessage,
                priority = -1
              ) {
                log =>
                  (
                    folderName,
                    runTestRunnerSubprocess(Task.dest / folderName, testClassList, workerResultSet)
                  )
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
    }
  }

  private def runTestQueueScheduler(
      filteredClassLists: Seq[Seq[String]]
  )(implicit ctx: mill.api.Ctx) = {

    val filteredClassCount = filteredClassLists.map(_.size).sum

    def prepareTestClassesFolder(selectors2: Seq[String], base: os.Path): os.Path = {
      // test-classes folder is used to store the test classes for the children test runners to claim from
      val testClassQueueFolder = base / "test-classes"
      os.makeDir.all(testClassQueueFolder)
      selectors2.zipWithIndex.foreach { case (s, i) =>
        os.write.over(testClassQueueFolder / s, Array.empty[Byte])
      }
      testClassQueueFolder
    }

    def runTestRunnerSubprocess(
        base: os.Path,
        testClassQueueFolder: os.Path,
        force: Boolean,
        logger: Logger,
        workerStatusMap: java.util.concurrent.ConcurrentMap[os.Path, String => Unit],
        workerResultSet: java.util.concurrent.ConcurrentMap[os.Path, Unit]
    ) = {
      val claimFolder = base / "claim"
      os.makeDir.all(claimFolder)

      val startingTestClass =
        try {
          os
            .list
            .stream(testClassQueueFolder)
            .map(TestRunnerUtils.claimFile(_, claimFolder))
            .collectFirst { case Some(name) => name }
        } catch {
          case e: Throwable => None
        }

      if (force || startingTestClass.nonEmpty) {
        startingTestClass.foreach(logger.ticker(_))
        // queue.log file will be appended by the runner with the stolen test class's name
        // it can be used to check the order of test classes of the runner
        val claimLog = claimFolder / os.up / s"${claimFolder.last}.log"
        os.write.over(claimLog, Array.empty[Byte])
        workerStatusMap.put(claimLog, logger.ticker)
        // test runner will log success/failure test class counter here while running
        val resultPath = base / s"result.log"
        os.write.over(resultPath, upickle.default.write((0L, 0L)))
        workerResultSet.put(resultPath, ())

        val result = callTestRunnerSubprocess(
          base,
          resultPath,
          Right((startingTestClass, testClassQueueFolder, claimFolder))
        )

        workerStatusMap.remove(claimLog)
        Some(result)
      } else {
        None
      }
    }

    def jobsProcessLength(numTests: Int) = {
      val cappedJobs = Math.max(Math.min(Task.ctx().jobs, numTests), 1)
      (cappedJobs, cappedJobs.toString.length)
    }

    val groupFolderData = filteredClassLists match {
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
          val paddedIndex = mill.internal.Util.leftPad(i.toString, maxLength, '0')
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

    val groupLength = groupFolderData.length
    val maxGroupLength = groupLength.toString.length

    val outputs = TestModuleUtil.withTestProgressTickerThread(filteredClassCount) {
      (workerStatusMap, workerResultSet) =>
        // We got "--jobs" threads, and "groupLength" test groups, so we will spawn at most jobs * groupLength runners here
        // In most case, this is more than necessary, and runner creation is expensive,
        // but we have a check for non-empty test-classes folder before really spawning a new runner, so in practice the overhead is low
        val subprocessFutures = for {
          ((groupFolder, testClassQueueFolder, numTests), groupIndex) <-
            groupFolderData.zipWithIndex
          // Don't have re-calculate for every processes
          groupName = groupFolder.last
          (jobs, maxProcessLength) = jobsProcessLength(numTests)
          paddedGroupIndex = mill.internal.Util.leftPad(groupIndex.toString, maxGroupLength, '0')
          processIndex <- 0 until Math.max(Math.min(jobs, numTests), 1)
        } yield {

          val paddedProcessIndex =
            mill.internal.Util.leftPad(processIndex.toString, maxProcessLength, '0')

          val processFolder = groupFolder / s"worker-$paddedProcessIndex"

          val label =
            if (groupFolderData.size == 1) paddedProcessIndex
            else s"$paddedGroupIndex-$paddedProcessIndex"

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
            logger =>
              val result = runTestRunnerSubprocess(
                processFolder,
                testClassQueueFolder,
                // force run when processIndex == 0 (first subprocess), even if there are no tests to run
                // to force the process to go through the test framework setup/teardown logic
                force = processIndex == 0,
                logger,
                workerStatusMap,
                workerResultSet
              )

              val claimedClasses =
                if (os.exists(processFolder / "claim")) os.list(processFolder / "claim").size else 0

              (claimedClasses, groupName, result)
          }
        }

        Task.fork.blocking {
          // We special-case this to avoid
          while ({
            val claimedCounts = subprocessFutures.flatMap(_.value).flatMap(_.toOption).map(_._1)
            !(
              (claimedCounts.sum == filteredClassCount && subprocessFutures.head.isCompleted) ||
                subprocessFutures.forall(_.isCompleted)
            )
          }) Thread.sleep(1)
        }

        subprocessFutures.flatMap(_.value).map(_.get)
    }

    val subprocessResult = {
      val failMap = mutable.Map.empty[String, String]
      val successMap = mutable.Map.empty[String, (String, Seq[TestResult])]

      outputs.foreach {
        case (_, name, Some(Result.Failure(v))) => failMap.updateWith(name) {
            case Some(old) => Some(old + " " + v)
            case None => Some(v)
          }
        case (_, name, Some(Result.Success((msg, results)))) => successMap.updateWith(name) {
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

}

private[scalalib] object TestModuleUtil {

  private def withTestProgressTickerThread[T](totalClassCount: Long)(
      body: (
          java.util.concurrent.ConcurrentMap[os.Path, String => Unit],
          java.util.concurrent.ConcurrentMap[os.Path, Unit]
      ) => T
  )(implicit ctx: mill.api.Ctx): T = {
    val workerStatusMap = new java.util.concurrent.ConcurrentHashMap[os.Path, String => Unit]()
    val workerResultSet = new java.util.concurrent.ConcurrentHashMap[os.Path, Unit]()

    val testClassTimeMap = new java.util.concurrent.ConcurrentHashMap[String, Long]()

    val executor = Executors.newScheduledThreadPool(1)
    try {
      // Periodically check the result log file and tick the relevant infos
      executor.scheduleWithFixedDelay(
        () => {
          // reuse to reduce syscall, may not be as accurate as running `System.currentTimeMillis()` inside each entry
          val now = System.currentTimeMillis()
          workerStatusMap.forEach { (claimLog, callback) =>
            // the last one is always the latest
            os.read.lines(claimLog).lastOption.foreach { currentTestClass =>
              testClassTimeMap.putIfAbsent(currentTestClass, now)
              val last = testClassTimeMap.get(currentTestClass)
              callback(s"$currentTestClass${mill.internal.Util.renderSecondsSuffix(now - last)}")
            }
          }
          var totalSuccess = 0L
          var totalFailure = 0L
          workerResultSet.forEach { (resultLog, _) =>
            val (success, failure) = upickle.default.read[(Long, Long)](os.read.stream(resultLog))
            totalSuccess += success
            totalFailure += failure
          }
          ctx.log.ticker(s"${totalSuccess + totalFailure}/${totalClassCount} completed${
              if totalFailure > 0 then s", ${totalFailure} failures." else "."
            }")
        },
        0,
        20,
        java.util.concurrent.TimeUnit.MILLISECONDS
      )
      body(workerStatusMap, workerResultSet)
    } finally executor.shutdown()
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
