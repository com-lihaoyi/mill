package mill.bsp

import mill.api.{Logger, SystemStreams, Result}

import java.io.PrintStream
import java.net.URL

private trait BspClasspathWorker {
  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logStream: PrintStream,
      logDir: os.Path,
      canReload: Boolean
  ): Result[BspServerHandle]
}

object BspClasspathWorker {

  private var worker: Option[BspClasspathWorker] = None

  def apply(
      workspace: os.Path,
      log: Logger,
      workerLibs: Option[Seq[URL]] = None
  ): Result[BspClasspathWorker] = {
    worker match {
      case Some(x) => Result.Success(x)
      case None =>
        val urlsOpt = workerLibs
          .map { urls =>
            log.debug("Using direct submitted worker libs")
            urls.map(url => os.Path(url.getPath))
          }
          .orElse {
            // load extra classpath entries from file
            val resources = s"${Constants.serverName}-${mill.main.BuildInfo.millVersion}.resources"
            val cpFile = workspace / Constants.bspDir / resources
            if (os.exists(cpFile)) {
              // TODO: if outdated, we could regenerate the resource file and re-load the worker

              // read the classpath from resource file
              log.debug(s"Reading worker classpath from file: ${cpFile}")
              Some(os.read(cpFile).linesIterator.map(u => os.Path(new URL(u).getPath)).toSeq)
            } else
              None
          }

        urlsOpt match {
          case Some(urls) =>
            // create classloader with bsp.worker and deps
            val cl = mill.util.Jvm.createClassLoader(urls, getClass().getClassLoader())

            val workerCls = cl.loadClass(Constants.bspWorkerImplClass)
            val ctr = workerCls.getConstructor()
            val workerImpl = ctr.newInstance().asInstanceOf[BspClasspathWorker]
            worker = Some(workerImpl)
            Result.Success(workerImpl)
          case None =>
            Result.Failure(
              "You need to run `mill mill.bsp.BSP/install` before you can use the BSP server"
            )
        }

    }
  }

}
