package mill.testrunner

import mill.api.{Logger, Ctx, DummyTestReporter, SystemStreams, internal}

@internal object TestRunnerMain0 {
  def main0(args: Array[String], classLoader: ClassLoader): Unit = {
    try {
      val testArgs = upickle.default.read[mill.testrunner.TestArgs](os.read(os.Path(args(1))))
      testArgs.sysProps.foreach { case (k, v) => System.setProperty(k, v) }

      val filter = TestRunnerUtils.globFilter(testArgs.globSelectors)

      val result = TestRunnerUtils.runTestFramework0(
        frameworkInstances = Framework.framework(testArgs.framework),
        testClassfilePath = Seq.from(testArgs.testCp),
        args = testArgs.arguments,
        classFilter = cls => filter(cls.getName),
        cl = classLoader,
        testReporter = DummyTestReporter
      )

      // Clear interrupted state in case some badly-behaved test suite
      // dirtied the thread-interrupted flag and forgot to clean up. Otherwise,
      // that flag causes writing the results to disk to fail
      Thread.interrupted()
      os.write(testArgs.outputPath, upickle.default.stream(result))
    } catch {
      case e: Throwable =>
        println(e)
        e.printStackTrace()
    }
    // Tests are over, kill the JVM whether or not anyone's threads are still running
    // Always return 0, even if tests fail. The caller can pick up the detailed test
    // results from the outputPath
    System.exit(0)
  }

}
