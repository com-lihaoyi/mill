package mill.scalalib.scalafmt

import ammonite.ops.Path
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

      Jvm.subprocess(
        "org.scalafmt.cli.Cli",
        scalafmtClasspath,
        mainArgs = toFormat
          .map(_.path.toString) ++ Seq("--config", scalafmtConfig.path.toString)
      )

      reformatted ++= toFormat.map { ref =>
        val updRef = PathRef(ref.path)
        updRef.path -> updRef.sig
      }
      configSig = scalafmtConfig.sig
    } else {
      ctx.log.info(s"Everything is formatted already")
    }

  }
}
