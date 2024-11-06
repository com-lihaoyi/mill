package mill.init

import coursier.LocalRepositories
import coursier.maven.MavenRepository
import mill.api.{Loose, PathRef}
import mill.define.{Discover, ExternalModule, TaskModule}
import mill.scalalib.scalafmt.ScalafmtWorkerModule
import mill.util.{Jvm, Util}
import mill.{Command, T, Task}

import scala.util.control.NoStackTrace

@mill.api.experimental
object InitMavenModule extends ExternalModule with InitMavenModule with TaskModule {

  lazy val millDiscover: Discover = Discover[this.type]
}

/**
 * Defines a [[InitModule.init task]] to convert a Maven build to Mill.
 */
@mill.api.experimental
trait InitMavenModule extends TaskModule {

  def defaultCommandName(): String = "init"

  /**
   * Classpath containing [[buildGenMainClass build file generator]].
   */
  def buildGenClasspath: T[Loose.Agg[PathRef]] = T {
    val repositories = Seq(
      LocalRepositories.ivy2Local,
      MavenRepository("https://repo1.maven.org/maven2"),
      MavenRepository("https://oss.sonatype.org/content/repositories/releases")
    )
    Util.millProjectModule("mill-main-maven", repositories)
  }

  /**
   * Mill build file generator application entrypoint.
   */
  def buildGenMainClass: T[String] = "mill.main.maven.BuildGen"

  /**
   * Scalafmt configuration file for formatting generated Mill build files.
   */
  def initScalafmtConfig: T[PathRef] = T {
    val config = millSourcePath / ".scalafmt.conf"
    if (!os.exists(config)) {
      T.log.info(s"creating Scalafmt configuration file $config ...")
      os.write(
        config,
        s"""version = "3.8.4-RC1"
           |runner.dialect = scala213
           |newlines.source=fold
           |newlines.topLevelStatementBlankLines = [
           |  {
           |    blanks { before = 1 }
           |  }
           |]
           |""".stripMargin
      )
    }
    PathRef(config)
  }

  /**
   * Generates and formats Mill build files for an existing Maven project.
   *
   * @param args arguments for the [[buildGenMainClass build file generator]]
   */
  def init(args: String*): Command[Unit] = Task.Command {
    val root = millSourcePath

    val mainClass = buildGenMainClass()
    val classPath = buildGenClasspath().map(_.path)
    val exit = Jvm.callSubprocess(
      mainClass = mainClass,
      classPath = classPath,
      mainArgs = args,
      workingDir = root
    ).exitCode

    if (exit == 0) {
      val files = os.walk.stream(root, skip = (root / "out").equals)
        .filter(_.ext == "mill")
        .map(PathRef(_))
        .toSeq
      val config = initScalafmtConfig()
      T.log.info("formatting Mill build files ...")
      ScalafmtWorkerModule.worker().reformat(files, config)

      T.log.info("init completed, run \"mill resolve _\" to list available tasks")
    } else {
      throw InitMavenException(s"$mainClass exit($exit)")
    }
  }
}

@mill.api.experimental
case class InitMavenException(message: String) extends Exception(message) with NoStackTrace
