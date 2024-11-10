package mill.testrunner

import java.net.URLClassLoader
import mill.api.JsonFormatters._
import mill.api.internal

@internal object DiscoverTestsMain {
  case class Args(
      classLoaderClasspath: Seq[os.Path],
      testClasspath: Seq[os.Path],
      testFramework: String
  )

  object Args {
    implicit def resultRW: upickle.default.ReadWriter[Args] = upickle.default.macroRW
  }

  def main(args: Array[String]): Unit = {
    main0(upickle.default.read[Args](os.read(os.Path(args(0)))))
  }

  def main0(args: Args): Unit = {
    val classLoader = new URLClassLoader(
      args.classLoaderClasspath.map(_.toIO.toURI().toURL()).toArray,
      null
    ) {
      override def findClass(name: String): Class[?] = {
        if (name.startsWith("sbt.testing")) {
          classOf[DiscoverTestsMain.type].getClassLoader().loadClass(name)
        } else {
          super.findClass(name)
        }
      }
    }
    val framework = Framework.framework(args.testFramework)(classLoader)
    TestRunnerUtils
      .discoverTests(classLoader, framework, args.testClasspath)
      .toSeq
      .map(_._1.getName())
      .map {
        case s if s.endsWith("$") => s.dropRight(1)
        case s => s
      }
      .foreach(println)
    System.exit(0)
  }
}
