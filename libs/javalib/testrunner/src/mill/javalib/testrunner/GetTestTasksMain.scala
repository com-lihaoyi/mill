package mill.javalib.testrunner

import mill.api.daemon.internal.internal

@internal object GetTestTasksMain {
  import mill.api.JsonFormatters.PathTokensReader
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
          Seq.from(testCp),
          args,
          cls => globFilter(cls.getName),
          classLoader
        )
        .toSeq
    }
  }

  def main(args: Array[String]): Unit = mainargs.ParserForMethods(this).runOrExit(args.toSeq)
}
