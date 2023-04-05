package mill.bsp

import java.io.{InputStream, PrintStream}
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration
import mill.api.{Ctx, DummyInputStream, Logger, PathRef, Result, SystemStreams}
import mill.{Agg, T, BuildInfo => MillBuildInfo}
import mill.define.{Command, Discover, ExternalModule, Task}
import mill.eval.Evaluator
import mill.main.{BspServerHandle, BspServerResult, BspServerStarter}
import mill.scalalib.{CoursierModule, Dep}
import mill.util.PrintLogger
import os.Path

object BSP extends ExternalModule with CoursierModule with BspServerStarter {
  import mill.main.TokenReaders._

  lazy val millDiscover: Discover[this.type] = Discover[this.type]

  private[this] val millServerHandle = Promise[BspServerHandle]()

  private def bspWorkerIvyDeps: T[Agg[Dep]] = T {
    Agg(Dep.parse(BuildInfo.millBspWorkerDep))
  }

  private def bspWorkerLibs: T[Agg[PathRef]] = {
    sys.props.getOrElse("MILL_UNDER_TEST", "0") match {
      case "1" => T {
          mill.modules.Util.millProjectModule(
            "MILL_BSP_WORKER",
            "mill-bsp-worker",
            repositoriesTask()
          )
        }
      case _ => T {
          resolveDeps(T.task {
            bspWorkerIvyDeps().map(bindDependency())
          })()
        }
    }
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
  def install(jobs: Int = 1): Command[(PathRef, String)] = T.command {
    // we create a file containing the additional jars to load
    val cpFile = T.workspace / Constants.bspDir / s"${Constants.serverName}.resources"
    os.write.over(
      cpFile,
      bspWorkerLibs().iterator.map(_.path.toNIO.toUri.toURL).mkString("\n"),
      createFolders = true
    )
    BspWorker(T.ctx()).map(_.createBspConnection(jobs, Constants.serverName))
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
      case Result.Success(worker) =>
        worker.startBspServer(
          initialEvaluator,
          streams,
          workspaceDir / Constants.bspDir,
          canReload,
          Seq(millServerHandle) ++ serverHandle.toSeq
        )
      case _ => BspServerResult.Failure
    }
  }

}
