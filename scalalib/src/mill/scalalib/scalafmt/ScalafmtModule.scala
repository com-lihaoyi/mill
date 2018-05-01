package mill.scalalib.scalafmt

import ammonite.ops._
import mill._
import mill.define.{Command, Sources, Worker}
import mill.scalalib._

trait ScalafmtModule extends ScalaModule {

  def reformat(): Command[Unit] = T.command {
    worker().reformat(
      filesToFormat(sources()),
      scalafmtConfig().head,
      scalafmtDeps().map(_.path)
    )
  }

  def scalafmtVersion: T[String] = "1.5.1"

  def scalafmtConfig: Sources = T.sources(pwd / ".scalafmt.conf")

  def scalafmtDeps: T[Agg[PathRef]] = T {
    Lib.resolveDependencies(
      scalaWorker.repositories,
      Lib.depToDependency(_, "2.12.4"),
      Seq(ivy"com.geirsson::scalafmt-cli:${scalafmtVersion()}")
    )
  }

  def worker: Worker[ScalafmtWorker] = T.worker { new ScalafmtWorker() }

  private def filesToFormat(sources: Seq[PathRef]) = {
    for {
      pathRef <- sources if exists(pathRef.path)
      file <- ls.rec(pathRef.path) if file.isFile && file.ext == "scala"
    } yield PathRef(file)
  }

}
