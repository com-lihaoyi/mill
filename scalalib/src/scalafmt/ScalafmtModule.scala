package mill.scalalib.scalafmt

import mill._
import mill.define._
import mill.scalalib._

trait ScalafmtModule extends JavaModule {

  def reformat(): Command[Unit] =
    T.command {
      ScalafmtWorkerModule
        .worker()
        .reformat(
          filesToFormat(sources()),
          scalafmtConfig().head
        )
    }

  def checkFormat(): Command[Unit] =
    T.command {
      ScalafmtWorkerModule
        .worker()
        .checkFormat(
          filesToFormat(sources()),
          scalafmtConfig().head
        )
    }

  def scalafmtConfig: Sources = T.sources(os.pwd / ".scalafmt.conf")

  protected def filesToFormat(sources: Seq[PathRef]) =
    for {
      pathRef <- sources if os.exists(pathRef.path)
      file    <- os.walk(pathRef.path) if os.isFile(file) && file.ext == "scala"
    } yield PathRef(file)

}

object ScalafmtModule extends ExternalModule with ScalafmtModule {

  def reformatAll(sources: mill.main.Tasks[Seq[PathRef]]): Command[Unit] =
    T.command {
      val files = T.sequence(sources.value)().flatMap(filesToFormat)
      ScalafmtWorkerModule
        .worker()
        .reformat(
          files,
          scalafmtConfig().head
        )
    }

  def checkFormatAll(sources: mill.main.Tasks[Seq[PathRef]]): Command[Unit] =
    T.command {
      val files = T.sequence(sources.value)().flatMap(filesToFormat)
      ScalafmtWorkerModule
        .worker()
        .checkFormat(
          files,
          scalafmtConfig().head
        )
    }

  implicit def millScoptTargetReads[T] = new mill.main.Tasks.Scopt[T]()

  lazy val millDiscover = Discover[this.type]
}
