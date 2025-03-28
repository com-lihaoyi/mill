package mill.bsp

import mill.api.{Ctx, DummyTestReporter, Logger, SystemStreams, Result}
import mill.scalalib.{CoursierModule, Dep}

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
  ): BspClasspathWorker = {
    worker.getOrElse {
      val jars: Seq[os.Path] = workerLibs
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
        .getOrElse {
          implicit val ctxForResolution: Ctx = new mill.api.Ctx.Impl(
            args = Vector(),
            dest0 = () => null,
            log = log,
            env = Map(),
            reporter = _ => None,
            testReporter = DummyTestReporter,
            workspace = mill.api.WorkspaceRoot.workspaceRoot,
            systemExit = _ => ???,
            fork = null,
            jobs = 1
          )

          CoursierModule.defaultResolver.classpath(Seq(Dep.millProjectModule("mill-bsp-worker")))
            .map(_.path)
        }

      // create classloader with bsp.worker and deps
      val cl = mill.util.Jvm.createClassLoader(jars, getClass().getClassLoader())

      val workerCls = cl.loadClass(Constants.bspWorkerImplClass)
      val ctr = workerCls.getConstructor()
      val workerImpl = ctr.newInstance().asInstanceOf[BspClasspathWorker]
      worker = Some(workerImpl)
      workerImpl
    }
  }

}
