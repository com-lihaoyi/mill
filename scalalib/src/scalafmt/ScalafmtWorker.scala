package mill.scalalib.scalafmt

import java.nio.file.{Paths => JPaths}

import mill._
import mill.define.{Discover, ExternalModule, Worker}
import mill.api.Ctx
import org.scalafmt.interfaces.Scalafmt

import scala.collection.mutable

object ScalafmtWorkerModule extends ExternalModule {
  def worker: Worker[ScalafmtWorker] = T.worker { new ScalafmtWorker() }

  lazy val millDiscover = Discover[this.type]
}

private[scalafmt] class ScalafmtWorker {
  private val reformatted: mutable.Map[os.Path, Int] = mutable.Map.empty
  private var configSig: Int = 0

  def reformat(input: Seq[PathRef],
               scalafmtConfig: PathRef)(implicit ctx: Ctx): Unit = {
    val toFormat =
      if (scalafmtConfig.sig != configSig) input
      else
        input.filterNot(ref => reformatted.get(ref.path).contains(ref.sig))

    if (toFormat.nonEmpty) {
      ctx.log.info(s"Formatting ${toFormat.size} Scala sources")
      reformatAction(toFormat.map(_.path),
                     scalafmtConfig.path)
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

  private def reformatAction(toFormat: Seq[os.Path],
                             config: os.Path)(implicit ctx: Ctx) = {
    val scalafmt =
      Scalafmt
        .create(this.getClass.getClassLoader)
        .withRespectVersion(false)

    val configPath =
      if (os.exists(config))
        config.toNIO
      else
        JPaths.get(getClass.getResource("default.scalafmt.conf").toURI)

    toFormat.foreach { pathToFormat =>
      val code = os.read(pathToFormat)
      val formatteCode = scalafmt.format(configPath, pathToFormat.toNIO, code)
      os.write.over(pathToFormat, formatteCode)
    }
  }
}
