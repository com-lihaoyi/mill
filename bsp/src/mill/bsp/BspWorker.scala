package mill.bsp

import mill.api.{Ctx, Logger, SystemStreams}
import os.Path

import java.io.PrintStream

private trait BspWorker {
  def startBspServer(
      streams: SystemStreams,
      logStream: PrintStream,
      logDir: os.Path,
      canReload: Boolean,
      projectDir: os.Path
  ): Either[String, BspServerHandle]
}

private object BspWorker {

  private[this] var worker: Option[BspWorker] = None

  def readConfig(workspace: os.Path): Either[String, BspServerConfig] = {
    val configFile =
      workspace / Constants.bspDir / s"${Constants.serverName}-${mill.main.BuildInfo.millVersion}.conf"
    if (!os.exists(configFile)) return Left(
      s"""Could not find config file: ${configFile}
         |You need to run `mill mill.bsp.BSP/install` before you can use the BSP server""".stripMargin
    )

    val config = upickle.default.read[BspServerConfig](
      os.read(configFile)
    )

    // TODO: if outdated, we could regenerate the resource file and re-load the worker
    Right(config)
  }

  def apply(
      workspace: os.Path,
      home0: os.Path,
      log: Logger
//      workerLibs: Option[Seq[URL]] = None
  ): Either[String, BspWorker] = {
    worker match {
      case Some(x) => Right(x)
      case None =>
//        val urls = workerLibs.map { urls =>
//          log.debug("Using direct submitted worker libs")
//          urls
//        }.getOrElse {
        // load extra config from file
        readConfig(workspace).map { config =>
          log.debug(s"BSP Server config: ${BspUtil.pretty(config)}")

          val urls = config.classpath.map(_.path.toNIO.toUri.toURL)

          // create classloader with bsp.worker and deps
          val cl = mill.api.ClassLoader.create(urls, getClass().getClassLoader())(
            new Ctx.Home {
              override def home: Path = home0
            }
          )

          val workerCls = cl.loadClass(Constants.bspWorkerImplClass)
          val ctr = workerCls.getConstructor()
          val workerImpl = ctr.newInstance().asInstanceOf[BspWorker]
          worker = Some(workerImpl)
          workerImpl
        }
    }
  }

}
