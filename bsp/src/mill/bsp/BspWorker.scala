package mill.bsp

import mill.api.{Ctx, Logger, SystemStreams, internal}
import mill.eval.Evaluator
import os.Path

import java.io.PrintStream
import java.net.URL

private trait BspWorker {
  def startBspServer(
      streams: SystemStreams,
      logStream: PrintStream,
      logDir: os.Path,
      canReload: Boolean
  ): Either[String, BspServerHandle]
}

private object BspWorker {

  private[this] var worker: Option[BspWorker] = None

  def apply(
      workspace: os.Path,
      home0: os.Path,
      log: Logger,
      workerLibs: Option[Seq[URL]] = None
  ): Either[String, BspWorker] = {
    worker match {
      case Some(x) => Right(x)
      case None =>
        val urls = workerLibs.map { urls =>
          log.debug("Using direct submitted worker libs")
          urls
        }.getOrElse {
          // load extra classpath entries from file
          val cpFile =
            workspace / Constants.bspDir / s"${Constants.serverName}-${mill.main.BuildInfo.millVersion}.resources"
          if (!os.exists(cpFile)) return Left(
            "You need to run `mill mill.bsp.BSP/install` before you can use the BSP server"
          )

          // TODO: if outdated, we could regenerate the resource file and re-load the worker

          // read the classpath from resource file
          log.debug(s"Reading worker classpath from file: ${cpFile}")
          os.read(cpFile).linesIterator.map(u => new URL(u)).toSeq
        }

        // create classloader with bsp.worker and deps
        val cl = mill.api.ClassLoader.create(urls, getClass().getClassLoader())(
          new Ctx.Home { override def home: Path = home0 }
        )

        val workerCls = cl.loadClass(Constants.bspWorkerImplClass)
        val ctr = workerCls.getConstructor()
        val workerImpl = ctr.newInstance().asInstanceOf[BspWorker]
        worker = Some(workerImpl)
        Right(workerImpl)
    }
  }

}
