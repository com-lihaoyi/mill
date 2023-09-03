package mill.scalalib.scalafmt

import mill._
import mill.api.Result
import mill.define.{ExternalModule, Discover}
import mill.scalalib._

trait ScalafmtModule extends JavaModule {

  def reformat(): Command[Unit] = T.command {
    ScalafmtWorkerModule
      .worker()
      .reformat(
        filesToFormat(sources()),
        resolvedScalafmtConfig()
      )
  }

  def checkFormat(): Command[Unit] = T.command {
    ScalafmtWorkerModule
      .worker()
      .checkFormat(
        filesToFormat(sources()),
        resolvedScalafmtConfig()
      )
  }

  def scalafmtConfig: T[Seq[PathRef]] = T.sources(
    T.workspace / ".scalafmt.conf",
    os.pwd / ".scalafmt.conf"
  )

  // TODO: Do we want provide some defaults or write a default file?
  private[ScalafmtModule] def resolvedScalafmtConfig: Task[PathRef] = T.task {
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
            os.isFile(file) && (file.ext == "scala" || file.ext == "sc")
          )
        } else {
          Seq(pathRef.path)
        }
      }
    } yield PathRef(file)
  }

}

object ScalafmtModule extends ExternalModule with ScalafmtModule {

  def reformatAll(sources: mill.main.Tasks[Seq[PathRef]]): Command[Unit] =
    T.command {
      val files = T.sequence(sources.value)().flatMap(filesToFormat)
      ScalafmtWorkerModule
        .worker()
        .reformat(
          files,
          resolvedScalafmtConfig()
        )
    }

  def checkFormatAll(sources: mill.main.Tasks[Seq[PathRef]]): Command[Unit] =
    T.command {
      val files = T.sequence(sources.value)().flatMap(filesToFormat)
      ScalafmtWorkerModule
        .worker()
        .checkFormat(
          files,
          resolvedScalafmtConfig()
        )
    }

  lazy val millDiscover = Discover[this.type]
}
