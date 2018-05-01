package mill.scalalib.scalafmt

import ammonite.ops.{Path, exists}
import mill.{Agg, PathRef}
import mill.modules.Jvm
import mill.util.Ctx

import scala.collection.mutable

private[scalafmt] class ScalafmtWorker {
  private val reformatted: mutable.Map[Path, Int] = mutable.Map.empty
  private var configSig: Int = 0

  def reformat(input: Seq[PathRef],
               scalafmtConfig: PathRef,
               scalafmtClasspath: Agg[Path])(implicit ctx: Ctx): Unit = {
    val toFormat =
      if (scalafmtConfig.sig != configSig) input
      else
        input.filterNot(ref => reformatted.get(ref.path).contains(ref.sig))

    if (toFormat.nonEmpty) {
      ctx.log.info(s"Formatting ${toFormat.size} Scala sources")
      reformatAction(toFormat.map(_.path),
                     scalafmtConfig.path,
                     scalafmtClasspath)
      reformatted ++= toFormat.map { ref =>
        val updRef = PathRef(ref.path)
        updRef.path -> updRef.sig
      }
      configSig = scalafmtConfig.sig
    } else {
      ctx.log.info(s"Everything is formatted already")
    }
  }

  private val cliFlags = Seq("--non-interactive", "--quiet")

  private def reformatAction(toFormat: Seq[Path],
                             config: Path,
                             classpath: Agg[Path])(implicit ctx: Ctx) = {
    val configFlags =
      if (exists(config)) Seq("--config", config.toString) else Seq.empty
    Jvm.subprocess(
      "org.scalafmt.cli.Cli",
      classpath,
      mainArgs = toFormat.map(_.toString) ++ configFlags ++ cliFlags
    )
  }

}
