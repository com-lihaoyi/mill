package mill.testrunner

import mill.api.Loose.Agg
import mill.api.internal

@internal object GetTestTasksMain {
  private implicit def PathTokensReader2: mainargs.TokensReader.Simple[os.Path] =
    mill.api.JsonFormatters.PathTokensReader2
  @mainargs.main
  def main(
      runCp: Seq[os.Path],
      testCp: Seq[os.Path],
      framework: String,
      selectors: Seq[String],
      args: Seq[String]
  ): Unit = {
    main0(runCp, testCp, framework, selectors, args).foreach(println)
  }

  def main0(
      runCp: Seq[os.Path],
      testCp: Seq[os.Path],
      framework: String,
      selectors: Seq[String],
      args: Seq[String]
  ): Seq[String] = {
    val globFilter = TestRunnerUtils.globFilter(selectors)
    mill.util.Jvm.withClassLoader(
      classPath = runCp,
      sharedPrefixes = Seq("sbt.testing.")
    ) { classLoader =>
      TestRunnerUtils
        .getTestTasks0(
          Framework.framework(framework),
          Agg.from(testCp),
          args,
          cls => globFilter(cls.getName),
          classLoader
        )
    }
  }

  def main(args: Array[String]): Unit = {
    mainargs.ParserForMethods(this).runOrExit(args)
    // Discovery is over, kill the JVM whether or not anyone's threads are still running.
    // Test frameworks may leave non-daemon threads behind: `getTestTasks` instantiates a
    // `Runner` and never calls `done()` on it, and e.g. ScalaTest spawns a non-daemon
    // "ScalaTest-dispatcher" thread as part of that. Returning normally would leave this
    // process alive forever, and `TestModuleUtil.runTests` waits for it to exit.
    System.out.flush()
    System.exit(0)
  }
}
