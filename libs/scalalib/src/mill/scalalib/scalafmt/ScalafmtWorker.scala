package mill.scalalib.scalafmt

import mill.*
import mill.api.{Discover, ExternalModule, PathRef, TaskCtx}
import mill.scalalib.*

import scala.collection.mutable
import mill.api.Result

object ScalafmtWorkerModule extends ExternalModule with JavaModule {

  /**
   * Classpath for running Palantir Java Format.
   */
  def scalafmtClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"org.scalameta:scalafmt-dynamic_2.13:${mill.javalib.api.Versions.scalafmtVersion}")
    )
  }

  def scalafmtClassLoader: Worker[java.net.URLClassLoader] = Task.Worker {
    mill.util.Jvm.createClassLoader(scalafmtClasspath().map(_.path))
  }

  def worker: Worker[ScalafmtWorker] = Task.Worker { new ScalafmtWorker(scalafmtClassLoader()) }

  lazy val millDiscover = Discover[this.type]
}

private[scalafmt] class ScalafmtWorker(cl: ClassLoader) extends AutoCloseable {
  private val reformatted: mutable.Map[os.Path, Int] = mutable.Map.empty
  private var configSig: Int = 0

  val scalafmt = cl.loadClass("org.scalafmt.interfaces.Scalafmt")
    .getMethod("create", classOf[java.lang.ClassLoader])
    .invoke(null, cl)

  def reformat(input: Seq[PathRef], scalafmtConfig: PathRef)(using ctx: TaskCtx): Unit = {
    reformatAction(input, scalafmtConfig, dryRun = false)
  }

  def checkFormat(input: Seq[PathRef], scalafmtConfig: PathRef)(using
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
  )(using ctx: TaskCtx): Seq[PathRef] = {

    // only consider files that have changed since last reformat
    val toConsider = reformatted.synchronized {
      if (scalafmtConfig.sig != configSig) input
      else input.filterNot(ref => reformatted.get(ref.path).contains(ref.sig))
    }

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
        reformatted.synchronized {
          reformatted += updRef.path -> updRef.sig
        }
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

  override def close(): Unit = {
    scalafmt
      .getClass
      .getMethods
      .filter(_.getName == "clear")
      .head
      .invoke(scalafmt)

    reformatted.synchronized { reformatted.clear() }
    configSig = 0
  }
}
