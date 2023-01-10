package mill.bsp

import mill.Agg
import mill.api.{Ctx, PathRef, Result, internal}
import mill.define.Task
import mill.eval.Evaluator
import mill.main.{BspServerHandle, BspServerResult}

import java.io.{InputStream, PrintStream}
import java.net.URL
import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

@internal
trait BspWorker {

  def createBspConnection(
      jobs: Int,
      serverName: String
  )(implicit ctx: Ctx): Unit

  def startBspServer(
      initialEvaluator: Option[Evaluator],
      outStream: PrintStream,
      errStream: PrintStream,
      inStream: InputStream,
      logDir: os.Path,
      canReload: Boolean,
      serverHandles: Seq[Promise[BspServerHandle]]
  ): BspServerResult

}

@internal
object BspWorker {

  private[this] var worker: Option[BspWorker] = None

  def apply(millCtx: Ctx.Workspace with Ctx.Home with Ctx.Log): Result[BspWorker] = {
    worker match {
      case Some(x) => Result.Success(x)
      case None =>
        // load extra classpath entries from file
        val cpFile = millCtx.workspace / Constants.bspDir / s"${Constants.serverName}.resources"
        if (!os.exists(cpFile)) return Result.Failure(
          "You need to run `mill mill.bsp.BSP/install` before you can use the BSP server"
        )

        // TODO: if outdated, we could regenerate the resource file and re-load the worker

        val urls = os.read(cpFile).linesIterator.map(u => new URL(u)).toSeq

        // create classloader with bsp.worker and deps
        val cl = mill.api.ClassLoader.create(urls, getClass().getClassLoader())(millCtx)
        val workerVersion = Try {
          val workerBuildInfo = cl.loadClass(Constants.bspWorkerBuildInfoClass)
          workerBuildInfo.getMethod("millBspWorkerVersion").invoke(null)
        } match {
          case Success(mill.BuildInfo.millVersion) => // same as Mill, everything is good
          case Success(workerVersion) =>
            millCtx.log.error(
              s"""BSP worker version ($workerVersion) does not match Mill version (${mill.BuildInfo.millVersion}).
                 |You need to run `mill mill.bsp.BSP/install` again.""".stripMargin
            )
          case Failure(e) =>
            millCtx.log.error(
              s"""Could not validate worker version number.
                |Error message: ${e.getMessage}
                |""".stripMargin)
        }

        val workerCls = cl.loadClass(Constants.bspWorkerImplClass)
        val ctr = workerCls.getConstructor()
        val workerImpl = ctr.newInstance().asInstanceOf[BspWorker]
        worker = Some(workerImpl)
        Result.Success(workerImpl)
    }
  }

}
