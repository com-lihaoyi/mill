package mill.scalalib.spotless

import mill._
import mill.define.{Command, Discover, ExternalModule, Sources, Task}
import mill.api.{PathRef, Result}
import mill.scalalib._
import mill.main.Tasks
import mainargs.arg

import java.net.URLClassLoader

trait SpotlessModule extends JavaModule {

  def reformat(): Command[Unit] = T.command {
    loadSpotlessWorker().map { worker =>
      worker.reformat(
        filesToFormat(sources()),
        resolvedSpotlessConfig()
      )
    }.getOrElse(Result.Failure("Failed to load Spotless worker"))
  }

  def checkFormat(): Command[Unit] = T.command {
    loadSpotlessWorker().map { worker =>
      worker.checkFormat(
        filesToFormat(sources()),
        resolvedSpotlessConfig()
      )
    }.getOrElse(Result.Failure("Failed to load Spotless worker"))
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

  private def loadSpotlessWorker(): Option[SpotlessWorker] = {
    try {
      // Dynamically load SpotlessWorker using URLClassLoader
      val urls = Seq( /* Paths to Spotless and dependencies JARs here */ )
      val classLoader = new URLClassLoader(urls.toArray, getClass.getClassLoader)
      val workerClass = classLoader.loadClass("mill.scalalib.spotless.SpotlessWorker")
      val worker = workerClass.getDeclaredConstructor().newInstance().asInstanceOf[SpotlessWorker]
      Some(worker)
    } catch {
      case ex: Exception =>
        println(s"Failed to load SpotlessWorker: ${ex.getMessage}")
        None
    }
  }
}

object SpotlessModule extends ExternalModule with SpotlessModule with TaskModule {
  override def defaultCommandName(): String = "reformatAll"

  def reformatAll(@arg(positional = true) sources: Tasks[Seq[PathRef]] =
    Tasks.resolveMainDefault("__.sources")): Command[Unit] = T.command {
    val files = T.sequence(sources.value)().flatMap(filesToFormat)
    loadSpotlessWorker().map { worker =>
      worker.reformat(
        files,
        resolvedSpotlessConfig()
      )
    }.getOrElse(Result.Failure("Failed to load Spotless worker"))
  }

  def checkFormatAll(@arg(positional = true) sources: Tasks[Seq[PathRef]] =
    Tasks.resolveMainDefault("__.sources")): Command[Unit] = T.command {
    val files = T.sequence(sources.value)().flatMap(filesToFormat)
    loadSpotlessWorker().map { worker =>
      worker.checkFormat(
        files,
        resolvedSpotlessConfig()
      )
    }.getOrElse(Result.Failure("Failed to load Spotless worker"))
  }

  lazy val millDiscover: Discover = Discover[this.type]
}
