package mill.scalalib.scalafmt

import mill._
import mill.define._
import mill.scalalib._

trait ScalafmtModule extends JavaModule {

  def reformat(): Command[Unit] = T.command {
    ScalafmtWorkerModule
      .worker()
      .reformat(
        filesToFormat(sources()),
        scalafmtConfig().head,
        scalafmtDeps().map(_.path)
      )
  }

  def scalafmtVersion: T[String] = "1.5.1"

  def scalafmtConfig: Sources = T.sources(os.pwd / ".scalafmt.conf")

  def scalafmtDeps: T[Agg[PathRef]] = T {
    Lib.resolveDependencies(
      zincWorker.repositories,
      Lib.depToDependency(_, "2.12.4"),
      Seq(ivy"com.geirsson::scalafmt-cli:${scalafmtVersion()}")
    )
  }

  protected def filesToFormat(sources: Seq[PathRef]) = {
    for {
      pathRef <- sources if os.exists(pathRef.path)
      file <- os.walk(pathRef.path) if os.isFile(file) && file.ext == "scala"
    } yield PathRef(file)
  }

}

object ScalafmtModule extends ExternalModule with ScalafmtModule {

  def reformatAll(sources: mill.main.Tasks[Seq[PathRef]]): Command[Unit] =
    T.command {
      val files = Task.sequence(sources.value)().flatMap(filesToFormat)
      ScalafmtWorkerModule
        .worker()
        .reformat(
          files,
          scalafmtConfig().head,
          scalafmtDeps().map(_.path)
        )
    }

  implicit def millScoptTargetReads[T] = new mill.main.Tasks.Scopt[T]()

  lazy val millDiscover = Discover[this.type]
}
