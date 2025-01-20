package mill.testrunner

import mill.api.Loose.Agg
import mill.api.{Ctx, internal}
import os.Path

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
    mill.util.Jvm.inprocess(
      runCp,
      classLoaderOverrideSbtTesting = true,
      isolated = true,
      closeContextClassLoaderWhenDone = false,
      classLoader =>
        TestRunnerUtils
          .getTestTasks0(
            Framework.framework(framework),
            Agg.from(testCp),
            args,
            cls => globFilter(cls.getName),
            classLoader
          )
    )(new Ctx.Home {
      def home: Path = os.home
    })
  }

  def main(args: Array[String]): Unit = mainargs.ParserForMethods(this).runOrExit(args)
}
