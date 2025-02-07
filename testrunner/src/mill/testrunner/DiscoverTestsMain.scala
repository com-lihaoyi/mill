package mill.testrunner

import mill.api.internal
import os.Path

@internal object DiscoverTestsMain {
  private implicit def PathTokensReader2: mainargs.TokensReader.Simple[os.Path] =
    mill.api.JsonFormatters.PathTokensReader2

  @mainargs.main
  def main(runCp: Seq[os.Path], testCp: Seq[os.Path], framework: String): Unit = {
    main0(runCp, testCp, framework).foreach(println)
  }
  def main0(runCp: Seq[os.Path], testCp: Seq[os.Path], framework: String): Seq[String] = {
    mill.util.Jvm.withClassLoader(
      classPath = runCp,
      sharedPrefixes = Seq("sbt.testing.")
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

  def main(args: Array[String]): Unit = mainargs.ParserForMethods(this).runOrExit(args)
}
