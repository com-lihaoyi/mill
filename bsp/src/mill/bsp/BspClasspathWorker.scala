package mill.bsp

import mill.runner.api.{Logger, SystemStreams, Result}

import java.io.PrintStream
import java.net.URL
import scala.util.boundary

private trait BspClasspathWorker {
  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logStream: PrintStream,
      logDir: os.Path,
      canReload: Boolean
  ): Result[mill.runner.api.BspServerHandle]
}

object BspClasspathWorker {

  private var worker: Option[BspClasspathWorker] = None

  def apply(
      workspace: os.Path,
      log: Logger,
      workerLibs: Option[Seq[URL]] = None
  ): Result[BspClasspathWorker] = boundary {
    worker match {
      case Some(x) => Result.Success(x)
      case None =>
        val urls = workerLibs.map { urls =>
          log.debug("Using direct submitted worker libs")
          urls.map(url => os.Path(url.getPath))
        }.getOrElse {
          // load extra classpath entries from file
          val resources = s"${Constants.serverName}-${mill.util.BuildInfo.millVersion}.resources"
          val cpFile = workspace / Constants.bspDir / resources
          if (!os.exists(cpFile)) boundary.break(Result.Failure(
            "You need to run `mill mill.bsp.BSP/install` before you can use the BSP server"
          ))

          // TODO: if outdated, we could regenerate the resource file and re-load the worker

          // read the classpath from resource file
          log.debug(s"Reading worker classpath from file: ${cpFile}")
          os.read(cpFile).linesIterator.map(u => os.Path(new URL(u).getPath)).toSeq
        }

        // create classloader with bsp.worker and deps
        val cl = mill.util.Jvm.createClassLoader(urls, getClass().getClassLoader())

        val workerCls = cl.loadClass(Constants.bspWorkerImplClass)
        val ctr = workerCls.getConstructor()
        val workerImpl = ctr.newInstance().asInstanceOf[BspClasspathWorker]
        worker = Some(workerImpl)
        Result.Success(workerImpl)
    }
  }

}
