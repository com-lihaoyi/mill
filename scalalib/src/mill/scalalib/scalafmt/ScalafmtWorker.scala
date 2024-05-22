package mill.scalalib.scalafmt

import mill._
import mill.define.{Discover, ExternalModule, Worker}
import mill.api.Ctx
import org.scalafmt.interfaces.Scalafmt

import scala.collection.mutable
import mill.api.Result

object ScalafmtWorkerModule extends ExternalModule {
  def worker: Worker[ScalafmtWorker] = T.worker { new ScalafmtWorker() }

  lazy val millDiscover: Discover[this.type] = Discover[this.type]
}

private[scalafmt] class ScalafmtWorker extends AutoCloseable {
  private val reformatted: mutable.Map[os.Path, Int] = mutable.Map.empty
  private var configSig: Int = 0
  private var scalafmtInstanceCache = Option.empty[(Long, Scalafmt)]

  def reformat(scalafmtClasspath: Agg[PathRef], input: Seq[PathRef], scalafmtConfig: PathRef)(implicit ctx: Ctx): Unit = {
    reformatAction(scalafmtClasspath, input, scalafmtConfig, dryRun = false)
  }

  def checkFormat(scalafmtClasspath: Agg[PathRef], input: Seq[PathRef], scalafmtConfig: PathRef)(implicit ctx: Ctx): Result[Unit] = {

    val misformatted = reformatAction(scalafmtClasspath, input, scalafmtConfig, dryRun = true)
    if (misformatted.isEmpty) {
      Result.Success(())
    } else {
      val out = ctx.log.outputStream
      for (u <- misformatted) {
        out.println(u.path.toString)
      }
      Result.Failure(s"Found ${misformatted.length} misformatted files")
    }
  }

  // run scalafmt over input files and return any files that changed
  // (only save changes to files if dryRun is false)
  private def reformatAction(
      scalafmtClasspath: Agg[PathRef],
      input: Seq[PathRef],
      scalafmtConfig: PathRef,
      dryRun: Boolean
  )(implicit ctx: Ctx): Seq[PathRef] = {

    // only consider files that have changed since last reformat
    val toConsider =
      if (scalafmtConfig.sig != configSig) input
      else input.filterNot(ref => reformatted.get(ref.path).contains(ref.sig))

    if (toConsider.nonEmpty) {

      if (dryRun) {
        ctx.log.info(s"Checking format of ${toConsider.size} Scala sources")
      } else {
        ctx.log.info(s"Formatting ${toConsider.size} Scala sources")
      }

      val scalafmtClasspathSig = scalafmtClasspath.hashCode
      val scalafmt = scalafmtInstanceCache match {
        case Some((sig, instance)) if sig == scalafmtClasspathSig =>
          instance
        case _ =>
          val classpathUrls = scalafmtClasspath.map(_.path.toIO.toURI.toURL).toSeq
          val loader = mill.api.ClassLoader.create(classpathUrls, parent = null, sharedPrefixes = Seq("org.scalafmt.interfaces"))
          val instance = Scalafmt.create(loader)
          scalafmtInstanceCache = Some((scalafmtClasspathSig, instance))
          instance
      }

      val configPath = scalafmtConfig.path.toNIO

      // keeps track of files that are misformatted
      val misformatted = mutable.ListBuffer.empty[PathRef]

      def markFormatted(path: PathRef) = {
        val updRef = PathRef(path.path)
        reformatted += updRef.path -> updRef.sig
      }

      toConsider.foreach { pathToFormat =>
        val code = os.read(pathToFormat.path)
        val formattedCode = scalafmt.format(configPath, pathToFormat.path.toNIO, code)

        if (code != formattedCode) {
          misformatted += pathToFormat
          if (!dryRun) {
            os.write.over(pathToFormat.path, formattedCode)
            markFormatted(pathToFormat)
          }
        } else {
          markFormatted(pathToFormat)
        }

      }

      configSig = scalafmtConfig.sig
      misformatted.toList
    } else {
      ctx.log.info(s"Everything is formatted already")
      Nil
    }
  }

  override def close(): Unit = {
    reformatted.clear()
    configSig = 0
  }
}
