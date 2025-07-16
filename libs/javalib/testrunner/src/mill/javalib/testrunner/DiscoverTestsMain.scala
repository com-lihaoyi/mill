package mill.javalib.testrunner

import mill.api.daemon.internal.internal

@internal object DiscoverTestsMain {
  import mill.api.JsonFormatters.PathTokensReader

  @mainargs.main
  def main(runCp: Seq[os.Path], testCp: Seq[os.Path], framework: String): Unit = {
    main0(runCp, testCp, framework).foreach(println)
  }
  def main0(runCp: Seq[os.Path], testCp: Seq[os.Path], framework: String): Seq[String] = {
    mill.util.Jvm.withClassLoader(
      classPath = runCp,
      sharedPrefixes = Seq("sbt.testing.", "mill.api.daemon.")
    ) { classLoader =>
      TestRunnerUtils
        .discoverTests(classLoader, Framework.framework(framework)(classLoader), testCp)
        .toSeq
        .map(_._1.getName())
        .map {
          case s if s.endsWith("$") => s.dropRight(1)
          case s => s
        }
    }
  }

  def main(args: Array[String]): Unit = mainargs.ParserForMethods(this).runOrExit(args.toSeq)
}
