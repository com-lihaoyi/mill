package mill.bsp

import java.io.{InputStream, PrintStream}
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration
import mill.api.{Ctx, DummyInputStream, Logger, PathRef, Result, SystemStreams}
import mill.{Agg, T, BuildInfo => MillBuildInfo}
import mill.define.{Command, Discover, ExternalModule, Task}
import mill.eval.Evaluator
import mill.main.{BspServerHandle, BspServerResult, BspServerStarter}
import mill.modules.Util.millProjectModule
import mill.scalalib.{CoursierModule, Dep}
import mill.util.PrintLogger
import os.Path

object BSP extends ExternalModule with CoursierModule with BspServerStarter {
  import mill.main.TokenReaders._

  lazy val millDiscover: Discover[this.type] = Discover[this.type]

  private[this] val millServerHandle = Promise[BspServerHandle]()

  private def bspWorkerLibs: T[Agg[PathRef]] = T {
    millProjectModule("mill-bsp-worker", repositoriesTask())
  }

  /**
   * Installs the mill-bsp server. It creates a json file
   * with connection details in the ./.bsp directory for
   * a potential client to find.
   *
   * If a .bsp folder with a connection file already
   * exists in the working directory, it will be
   * overwritten and a corresponding message will be displayed
   * in stdout.
   *
   * If the creation of the .bsp folder fails due to any other
   * reason, the message and stacktrace of the exception will be
   * printed to stdout.
   */
  def install(jobs: Int = 1): Command[(PathRef, ujson.Value)] = T.command {
    // we create a file containing the additional jars to load
    val libUrls = bspWorkerLibs().map(_.path.toNIO.toUri.toURL).iterator.toSeq
    val cpFile =
      T.workspace / Constants.bspDir / s"${Constants.serverName}-${mill.BuildInfo.millVersion}.resources"
    os.write.over(
      cpFile,
      libUrls.mkString("\n"),
      createFolders = true
    )
    BspWorker(T.ctx(), Some(libUrls)).map(_.createBspConnection(jobs, Constants.serverName))
  }

  /**
   * This command only starts a BSP session, which means it injects the current evaluator into an already running BSP server.
   * This command requires Mill to start with `--bsp` option.
   * @param ev The Evaluator
   * @return The server result, indicating if mill should re-run this command or just exit.
   */
  def startSession(ev: Evaluator): Command[BspServerResult] = T.command {
    T.log.errorStream.println("BSP/startSession: Starting BSP session")
    val serverHandle: BspServerHandle = Await.result(millServerHandle.future, Duration.Inf)
    val res = serverHandle.runSession(ev)
    T.log.errorStream.println(s"BSP/startSession: Finished BSP session, result: ${res}")
    res
  }

  override def startBspServer(
      initialEvaluator: Option[Evaluator],
      streams: SystemStreams,
      logStream: Option[PrintStream],
      workspaceDir: os.Path,
      ammoniteHomeDir: os.Path,
      canReload: Boolean,
      serverHandle: Option[Promise[BspServerHandle]] = None
  ): BspServerResult = {
    val ctx = new Ctx.Workspace with Ctx.Home with Ctx.Log {
      override def workspace: Path = workspaceDir
      override def home: Path = ammoniteHomeDir
      // This all goes to the BSP log file mill-bsp.stderr
      override def log: Logger = new Logger {
        override def colored: Boolean = false
        override def systemStreams: SystemStreams = new SystemStreams(
          out = streams.out,
          err = streams.err,
          in = DummyInputStream
        )
        override def info(s: String): Unit = streams.err.println(s)
        override def error(s: String): Unit = streams.err.println(s)
        override def ticker(s: String): Unit = streams.err.println(s)
        override def debug(s: String): Unit = streams.err.println(s)
        override def debugEnabled: Boolean = true
      }
    }

    val worker = BspWorker(ctx)

    worker match {
      case Result.Success(worker, _) =>
        worker.startBspServer(
          initialEvaluator,
          streams,
          logStream.getOrElse(streams.err),
          workspaceDir / Constants.bspDir,
          canReload,
          Seq(millServerHandle) ++ serverHandle.toSeq
        )
      case f: Result.Failure[_] =>
        streams.err.println("Failed to start the BSP worker. " + f.msg)
        BspServerResult.Failure
      case f: Result.Exception =>
        streams.err.println("Failed to start the BSP worker. " + f.throwable)
        BspServerResult.Failure
      case f =>
        streams.err.println("Failed to start the BSP worker. " + f)
        BspServerResult.Failure
    }
  }

}
