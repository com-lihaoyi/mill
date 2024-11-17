package mill.testrunner

import mill.api.JsonFormatters.*
import mill.api.Loose.Agg
import mill.api.internal

import java.net.URLClassLoader

@internal object GetTestTasksMain {
  case class Args(
      classLoaderClasspath: Seq[os.Path],
      testClasspath: Seq[os.Path],
      testFramework: String,
      selectors: Seq[String],
      args: Seq[String]
  )

  object Args {
    implicit def resultRW: upickle.default.ReadWriter[Args] = upickle.default.macroRW
  }

  def main(args: Array[String]): Unit = {
    main0(upickle.default.read[Args](os.read(os.Path(args(0)))))
  }

  def main0(args: Args): Unit = {
    val globFilter = TestRunnerUtils.globFilter(args.selectors)
    val classLoader = new URLClassLoader(
      args.classLoaderClasspath.map(_.toIO.toURI().toURL()).toArray,
      getClass.getClassLoader
    )
    TestRunnerUtils
      .getTestTasks0(
        Framework.framework(args.testFramework),
        Agg.from(args.testClasspath),
        args.args,
        cls => globFilter(cls.getName),
        classLoader
      )
      .foreach(println)
    System.exit(0)
  }
}
