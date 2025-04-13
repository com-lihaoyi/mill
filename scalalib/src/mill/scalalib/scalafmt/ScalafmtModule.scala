package mill.scalalib.scalafmt

import scala.jdk.CollectionConverters.CollectionHasAsScala

import mill.*
import mill.constants.CodeGenConstants.buildFileExtensions
import mill.api.Result
import mill.define.{Discover, ExternalModule, TaskModule}
import mill.scalalib.*
import mainargs.arg
import mill.util.Tasks
import mill.util.Jvm

trait ScalafmtModule extends JavaModule {

  def reformat(): Command[Unit] = Task.Command {
    ScalafmtWorkerModule
      .worker()
      .reformat(
        filesToFormat(sources()),
        resolvedScalafmtConfig()
      )
  }

  def checkFormat(): Command[Unit] = Task.Command {
    ScalafmtWorkerModule
      .worker()
      .checkFormat(
        filesToFormat(sources()),
        resolvedScalafmtConfig()
      )
  }

  def scalafmtConfig: T[Seq[PathRef]] = Task.Sources(
    Task.workspace / ".scalafmt.conf",
    os.pwd / ".scalafmt.conf"
  )

  // TODO: Do we want provide some defaults or write a default file?
  private[ScalafmtModule] def resolvedScalafmtConfig: Task[PathRef] = Task.Anon {
    val locs = scalafmtConfig()
    locs.find(p => os.exists(p.path)) match {
      case None => Result.Failure(
          s"None of the specified `scalafmtConfig` locations exist. Searched in: ${locs.map(_.path).mkString(", ")}"
        )
      case Some(c) if (os.read.lines.stream(c.path).find(_.trim.startsWith("version")).isEmpty) =>
        Result.Failure(
          s"""Found scalafmtConfig file does not specify the scalafmt version to use.
             |Please specify the scalafmt version in ${c.path}
             |Example:
             |version = "2.4.3"
             |""".stripMargin
        )
      case Some(c) => Result.Success(c)
    }
  }

  protected def filesToFormat(sources: Seq[PathRef]) = {
    for {
      pathRef <- sources if os.exists(pathRef.path)
      file <- {
        if (os.isDir(pathRef.path)) {
          os.walk(pathRef.path).filter(file =>
            os.isFile(file) && (file.ext == "scala" || buildFileExtensions.asScala.exists(ex =>
              file.last.endsWith(s".$ex")
            ))
          )
        } else {
          Seq(pathRef.path)
        }
      }
    } yield PathRef(file)
  }

}

object ScalafmtModule extends ExternalModule with ScalafmtModule with TaskModule {
  override def defaultCommandName(): String = "reformatAll"

  def reformatAll(@arg(positional = true) sources: Tasks[Seq[PathRef]] =
    Tasks.resolveMainDefault("__.sources")) =
    Task.Command {
      val files = Task.sequence(sources.value)().flatMap(filesToFormat)
      ScalafmtWorkerModule
        .worker()
        .reformat(
          files,
          resolvedScalafmtConfig()
        )
    }

  def checkFormatAll(
      @arg(positional = true) sources: Tasks[Seq[PathRef]] = Tasks.resolveMainDefault("__.sources")
  ): Command[Unit] =
    Task.Command {
      val files = Task.sequence(sources.value)().flatMap(filesToFormat)
      ScalafmtWorkerModule
        .worker()
        .checkFormat(
          files,
          resolvedScalafmtConfig()
        )
    }

  /**
   * Class path of the scalafmt CLI
   */
  def scalafmtClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        ivy"org.scalameta:scalafmt-cli_2.13:${mill.scalalib.api.Versions.scalafmtVersion}"
      )
    )
  }

  /**
   * Main class of the scalafmt CLI
   *
   * Added in case the main class changes in the future, and users need to change it.
   */
  protected def scalafmtMainClass: String = "org.scalafmt.cli.Cli"

  /**
   * Runs the scalafmt CLI with the given arguments in an external process.
   *
   * Note that this runs the scalafmt CLI on the JVM, rather than a native launcher of the CLI.
   */
  def scalafmt(args: String*): Command[Unit] = Task.Command {
    Jvm.callProcess(
      scalafmtMainClass,
      args,
      classPath = scalafmtClasspath().map(_.path),
      cwd = T.workspace,
      stdin = os.Inherit,
      stdout = os.Inherit
    )
  }

  lazy val millDiscover = Discover[this.type]
}
