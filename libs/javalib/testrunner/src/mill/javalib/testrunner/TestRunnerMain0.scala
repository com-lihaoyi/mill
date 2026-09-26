package mill.javalib.testrunner

import mill.api.daemon.internal.{TestReporter, internal}
import mill.api.internal.PathAliasing

@internal object MillTestRunnerMain0 {
  def main0(args: Array[String], classLoader: ClassLoader): Unit = {
    PathAliasing.withDefaultPathSerializer {
      try {
        val testArgs = upickle.read[TestArgs](os.read(os.Path(args(1))))
        testArgs.sysProps.foreach { case (k, v) => System.setProperty(k, v) }

        val result = testArgs.globSelectors match {
          case Left(selectors) =>
            // `selectors` may already be the batch scheduler's per-group, resolved list, so
            // matching uses it, but explicitness must still read the original `testArgs.rawSelectors`.
            val classFilter =
              ClassFilter(matchSelectors = selectors, rawSelectors = Some(testArgs.rawSelectors))
            TestRunnerUtils.runTestFramework0(
              frameworkInstances = Framework.framework(testArgs.framework),
              testClassfilePath = Seq.from(testArgs.testCp),
              args = testArgs.arguments,
              classFilter = classFilter,
              cl = classLoader,
              testReporter = TestReporter(testArgs.logLevel),
              discoveredTestClasses = testArgs.discoveredTestClasses,
              resultPathOpt = Some(testArgs.resultPath)
            )
          case Right((startingTestClass, testClassQueueFolder, claimFolder)) =>
            TestRunnerUtils.queueTestFramework0(
              frameworkInstances = Framework.framework(testArgs.framework),
              testClassfilePath = Seq.from(testArgs.testCp),
              args = testArgs.arguments,
              startingTestClass = startingTestClass,
              testClassQueueFolder = testClassQueueFolder,
              claimFolder = claimFolder,
              cl = classLoader,
              testReporter = TestReporter(testArgs.logLevel),
              discoveredTestClasses = testArgs.discoveredTestClasses,
              resultPath = testArgs.resultPath,
              classFilter = ClassFilter(testArgs.rawSelectors)
            )
        }

        // Clear interrupted state in case some badly-behaved test suite
        // dirtied the thread-interrupted flag and forgot to clean up. Otherwise,
        // that flag causes writing the results to disk to fail
        Thread.interrupted()
        os.write(testArgs.outputPath, upickle.stream(result))
      } catch {
        case e: Throwable =>
          println(e)
          e.printStackTrace()
      }
    }
    // Tests are over, kill the JVM whether or not anyone's threads are still running
    // Always return 0, even if tests fail. The caller can pick up the detailed test
    // results from the outputPath
    System.exit(0)
  }

}
