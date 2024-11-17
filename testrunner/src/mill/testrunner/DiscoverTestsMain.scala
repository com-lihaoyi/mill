package mill.testrunner

import mill.api.internal

import mill.api.JsonFormatters.PathTokensReader
import java.net.URLClassLoader

@internal object DiscoverTestsMain {
  @mainargs.main
  def main(runCp: Seq[os.Path],
           testCp: Seq[os.Path],
           framework: String): Unit = {
    main0(runCp, testCp, framework).foreach(println)
  }
  def main0(runCp: Seq[os.Path],
            testCp: Seq[os.Path],
            framework: String): Seq[String] = {
    val classLoader = new URLClassLoader(
      runCp.map(_.toIO.toURI().toURL()).toArray,
      getClass.getClassLoader
    )
    try TestRunnerUtils
      .discoverTests(classLoader, Framework.framework(framework)(classLoader), testCp)
      .toSeq
      .map(_._1.getName())
      .map {
        case s if s.endsWith("$") => s.dropRight(1)
        case s => s
      }
    finally classLoader.close()
  }

  def main(args: Array[String]): Unit = mainargs.ParserForMethods(this).runOrExit(args)
}
