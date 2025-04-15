package mill.bsp

import mill.api.{Logger, SystemStreams, Result}
import mill.api.internal.BspServerHandle
import mill.define.TaskCtx
import mill.scalalib.{CoursierModule, Dep}

import java.net.URL

private trait BspClasspathWorker {
  def startBspServer(
      topLevelBuildRoot: os.Path,
      streams: SystemStreams,
      logDir: os.Path,
      canReload: Boolean
  ): Result[BspServerHandle]
}

object BspClasspathWorker {

  private var worker: Option[BspClasspathWorker] = None

  def apply(
      workspace: os.Path,
      workerLibs: Option[Seq[URL]] = None
  ): BspClasspathWorker = {
    worker.getOrElse {
      val jars: Seq[os.Path] = workerLibs
        .map { urls =>
          println("Using direct submitted worker libs")
          urls.map(url => os.Path(url.getPath))
        }
        .orElse {
          // load extra classpath entries from file
          val resources = s"${Constants.serverName}-${mill.util.BuildInfo.millVersion}.resources"
          val cpFile = workspace / Constants.bspDir / resources
          if (os.exists(cpFile)) {
            // TODO: if outdated, we could regenerate the resource file and re-load the worker

            // read the classpath from resource file
            println(s"Reading worker classpath from file: ${cpFile}")
            Some(os.read(cpFile).linesIterator.map(u => os.Path(new URL(u).getPath)).toSeq)
          } else
            None
        }
        .getOrElse {
          implicit val ctxForResolution: TaskCtx = null

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
