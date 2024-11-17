package mill.testrunner

import mill.api.JsonFormatters._
import mill.api.Loose.Agg
import mill.api.internal

import java.net.URLClassLoader

@internal object GetTestTasksMain {
  @mainargs.main
  def main(      runCp: Seq[os.Path],
                  testCp: Seq[os.Path],
                  framework: String,
                  selectors: Seq[String],
                  args: Seq[String]): Unit = {
    main0(runCp, testCp, framework, selectors, args).foreach(println)
  }

  def   main0(      runCp: Seq[os.Path],
                  testCp: Seq[os.Path],
                  framework: String,
                  selectors: Seq[String],
                  args: Seq[String]): Seq[String] = {
    val globFilter = TestRunnerUtils.globFilter(selectors)
    val classLoader = new URLClassLoader(
      runCp.map(_.toIO.toURI().toURL()).toArray,
      getClass.getClassLoader
    )
    try TestRunnerUtils
      .getTestTasks0(
        Framework.framework(framework),
        Agg.from(testCp),
        args,
        cls => globFilter(cls.getName),
        classLoader
      )
    finally classLoader.close()
  }

  def main(args: Array[String]): Unit = mainargs.ParserForMethods(this).runOrExit(args)
}
