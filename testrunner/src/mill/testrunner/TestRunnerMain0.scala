package mill.testrunner

import mill.api.Loose.Agg
import mill.api.{Ctx, DummyTestReporter, SystemStreams, internal}
import mill.util.PrintLogger

@internal object TestRunnerMain0 {
  def main0(args: Array[String], classLoader: ClassLoader): Unit = {
    try {
      val testArgs = upickle.default.read[mill.testrunner.TestArgs](os.read(os.Path(args(1))))
      val ctx = new Ctx.Log with Ctx.Home {
        val log = new PrintLogger(
          testArgs.colored,
          true,
          if (testArgs.colored) fansi.Color.Blue
          else fansi.Attrs.Empty,
          if (testArgs.colored) fansi.Color.Red
          else fansi.Attrs.Empty,
          new SystemStreams(System.out, System.err, System.in),
          debugEnabled = false,
          context = "",
          new PrintLogger.State()
        )
        val home = testArgs.home
      }
      ctx.log.debug(s"Setting ${testArgs.sysProps.size} system properties")
      testArgs.sysProps.foreach { case (k, v) => System.setProperty(k, v) }

      val filter = TestRunnerUtils.globFilter(testArgs.globSelectors)

      val result = TestRunnerUtils.runTestFramework0(
        frameworkInstances = Framework.framework(testArgs.framework),
        testClassfilePath = Agg(testArgs.testCp),
        args = testArgs.arguments,
        classFilter = filter,
        cl = classLoader,
        testReporter = DummyTestReporter
      )(ctx)

      // Clear interrupted state in case some badly-behaved test suite
      // dirtied the thread-interrupted flag and forgot to clean up. Otherwise
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
