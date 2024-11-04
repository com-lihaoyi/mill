package mill.scalalib.spotless

import mill._
import mill.define.{Command, Discover, ExternalModule, Sources, Task}
import mill.api.{PathRef, Result}
import mill.scalalib._
import mill.main.Tasks
import mainargs.arg

trait SpotlessModule extends JavaModule {

  def reformat(): Command[Unit] = T.command {
    SpotlessWorkerModule
      .worker()
      .reformat(
        filesToFormat(sources()),
        resolvedSpotlessConfig()
      )
  }

  def checkFormat(): Command[Unit] = T.command {
    SpotlessWorkerModule
      .worker()
      .checkFormat(
        filesToFormat(sources()),
        resolvedSpotlessConfig()
      )
  }

  def spotlessConfig: T[Seq[PathRef]] = T.sources(
    T.workspace / ".spotless.conf",
    os.pwd / ".spotless.conf"
  )

  private[SpotlessModule] def resolvedSpotlessConfig: Task[PathRef] = T.task {
    val locs = spotlessConfig()
    locs.find(p => os.exists(p.path)) match {
      case None => Result.Failure(
        s"None of the specified `spotlessConfig` locations exist. Searched in: ${locs.map(_.path).mkString(", ")}"
      )
      case Some(c) => Result.Success(c)
    }
  }

  protected def filesToFormat(sources: Seq[PathRef]): Seq[PathRef] = {
    for {
      pathRef <- sources if os.exists(pathRef.path)
      file <- {
        if (os.isDir(pathRef.path)) {
          os.walk(pathRef.path).filter(file =>
            os.isFile(file) && Seq("scala", "java", "kt").contains(file.ext)
          )
        } else {
          Seq(pathRef.path)
        }
      }
    } yield PathRef(file)
  }
}

object SpotlessModule extends ExternalModule with SpotlessModule with TaskModule {
  override def defaultCommandName(): String = "reformatAll"

  def reformatAll(@arg(positional = true) sources: Tasks[Seq[PathRef]] =
    Tasks.resolveMainDefault("__.sources")): Command[Unit] = T.command {
    val files = T.sequence(sources.value)().flatMap(filesToFormat)
    SpotlessWorkerModule
      .worker()
      .reformat(
        files,
        resolvedSpotlessConfig()
      )
  }

  def checkFormatAll(@arg(positional = true) sources: Tasks[Seq[PathRef]] =
    Tasks.resolveMainDefault("__.sources")): Command[Unit] = T.command {
    val files = T.sequence(sources.value)().flatMap(filesToFormat)
    SpotlessWorkerModule
      .worker()
      .checkFormat(
        files,
        resolvedSpotlessConfig()
      )
  }

  lazy val millDiscover: Discover = Discover[this.type]
}