package mill.scalalib.scalafmt.worker

import mill.scalalib.scalafmt.ScalafmtWorker
import mill.api.daemon.Result
import mill.api.{PathRef, TaskCtx}
import org.scalafmt.dynamic.coursier.CoursierDependencyDownloader
import org.scalafmt.interfaces.{
  RepositoryPackageDownloaderFactory,
  RepositoryProperties,
  Scalafmt,
  ScalafmtReporter
}

import scala.collection.mutable

private[scalafmt] class ScalafmtWorkerImpl() extends ScalafmtWorker {

  private val lock = new Object
  private val reformatted: mutable.Map[os.Path, Int] = mutable.Map.empty
  private var configSig: Int = 0

  private val scalafmt = Scalafmt
    .create(getClass().getClassLoader())
    .withRepositoryPackageDownloader(
      new RepositoryPackageDownloaderFactory {
        override def create(reporter: ScalafmtReporter, props: RepositoryProperties) =
          CoursierDependencyDownloader(Seq.empty)
      }
    )

  override def reformat(input: Seq[PathRef], scalafmtConfig: PathRef)(using ctx: TaskCtx): Unit = {
    reformatAction(input, scalafmtConfig, dryRun = false)
  }

  override def checkFormat(input: Seq[PathRef], scalafmtConfig: PathRef)(using
      ctx: TaskCtx
  ): Result[Unit] = {

    val misformatted = reformatAction(input, scalafmtConfig, dryRun = true)
    if (misformatted.isEmpty) {
      Result.Success(())
    } else {
      val out = ctx.log.streams.out
      for (u <- misformatted) {
        out.println(u.path.toString)
      }
      Result.Failure(s"Found ${misformatted.length} misformatted files")
    }
  }

  // run scalafmt over input files and return any files that changed
  // (only save changes to files if dryRun is false)
  private def reformatAction(
      input: Seq[PathRef],
      scalafmtConfig: PathRef,
      dryRun: Boolean
  )(using ctx: TaskCtx): Seq[PathRef] = lock.synchronized {

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

      val configPath = scalafmtConfig.path.toNIO

      // keeps track of files that are misformatted
      val misformatted = mutable.ListBuffer.empty[PathRef]

      def markFormatted(path: PathRef) = {
        val updRef = PathRef(path.path)
        reformatted += updRef.path -> updRef.sig
      }

      toConsider.foreach { pathToFormat =>
        val code = os.read(pathToFormat.path)
        val formattedCode = scalafmt
          .getClass
          .getMethods
          .filter(_.getName == "format")
          .head
          .invoke(scalafmt, configPath, pathToFormat.path.toNIO, code)
          .asInstanceOf[String]

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

  override def close(): Unit = lock.synchronized {
    scalafmt.clear()
    reformatted.clear()
    configSig = 0
  }

}
