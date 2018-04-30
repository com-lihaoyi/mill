package mill.scalalib.scalafmt

import ammonite.ops.{exists, ls}
import mill._
import mill.define.Command
import mill.modules.Jvm
import mill.scalalib._

trait ScalafmtModule extends JavaModule {

  def reformat(): Command[Unit] = T.command {
    val files = filesToFormat(sources())
    T.ctx().log.info(s"Formatting ${files.size} Scala sources")

    Jvm.subprocess(
      "org.scalafmt.cli.Cli",
      scalafmtDeps().map(_.path),
      mainArgs = files.map(_.toString)
    )
    ()
  }

  def scalafmtVersion: T[String] = "1.5.1"

  def scalafmtDeps = resolveDeps(
    T { Agg(ivy"com.geirsson::scalafmt-cli:${scalafmtVersion()}") }
  )

  private def filesToFormat(sources: Seq[PathRef]) = {
    for {
      pathRef <- sources if exists(pathRef.path)
      file <- ls.rec(pathRef.path) if file.isFile && file.ext == "scala"
    } yield file
  }

}
