package mill.javalib.zinc

import mill.api.SystemStreamsUtils
import mill.api.daemon.{DummyInputStream, SystemStreams}
import mill.client.lock.Locks
import mill.rpc.MillRpcWireTransport
import mill.server.Server
import pprint.{TPrint, TPrintColors}

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import scala.util.Using

/** Entry point for the Zinc worker subprocess. */
object ZincWorkerMain {
  def main(args: Array[String]): Unit = SystemStreamsUtils.withTopLevelSystemStreamProxy {
    args match {
      case Array(daemonDir, jobsStr) =>
        val server = ZincWorkerTcpServer(os.Path(daemonDir), jobsStr.toInt)
        server.run()

      case other =>
        Console.err.println(
          s"""Usage: zinc-worker <daemonDir> <jobs>
             |
             |Given: ${other.mkString(" ")}
             |""".stripMargin
        )
        sys.exit(1)
    }
  }

  private class ZincWorkerTcpServer(daemonDir: os.Path, jobs: Int) extends Server(Server.Args(
        daemonDir,
        // The worker kills the process when it needs to.
        acceptTimeout = None,
        Locks.files(daemonDir.toString),
        bufferSize = 4 * 1024
      )) {
    private val className = summon[TPrint[ZincWorkerTcpServer]].render(using TPrintColors.Colors)

    /**
     * Shared instance of the Zinc worker.
     *
     * It is very important that the same instance is used in all connections as it contains the necessary caches
     * to make Scala compilation fast!
     */
    private val worker = ZincWorker(jobs = jobs)

    protected class WriteSynchronizer

    override protected type PreHandleConnectionData = WriteSynchronizer

    override protected def preHandleConnection(
        connectionData: ConnectionData,
        stopServer: Server.StopServer
    ): WriteSynchronizer = new WriteSynchronizer

    override protected def handleConnection(
        connectionData: ConnectionData,
        stopServer: Server.StopServer,
        setIdle: Server.SetIdle,
        writeSynchronizer: WriteSynchronizer
    ) = {
      import connectionData.socketInfo

      val serverName = s"$className{${socketInfo.remote} -> ${socketInfo.local}}"
      Using.Manager { use =>
        val stdin = use(BufferedReader(InputStreamReader(connectionData.clientToServer)))
        val stdout = use(PrintStream(connectionData.serverToClient))
        val transport =
          MillRpcWireTransport(serverName, stdin, stdout, writeSynchronizer)
        val server = ZincWorkerRpcServer(worker, serverName, transport, setIdle, serverLog)

        // Make sure stdout and stderr is sent to the client
        SystemStreamsUtils.withStreams(SystemStreams(
          out = PrintStream(server.clientStdout.asStream),
          err = PrintStream(server.clientStderr.asStream),
          in = DummyInputStream
        )) {
          serverLog("server.run() starting")
          server.run()
          serverLog("server.run() finished")
        }
      }.get
    }

    override protected def onExceptionInHandleConnection(
        connectionData: ConnectionData,
        stopServer: Server.StopServer,
        writeSynchronizer: WriteSynchronizer,
        exception: Throwable
    ): Unit = {}

    override protected def beforeSocketClose(
        connectionData: ConnectionData,
        stopServer: Server.StopServer,
        writeSynchronizer: WriteSynchronizer
    ): Unit = {}

    override protected def checkIfClientAlive(
        connectionData: ConnectionData,
        stopServer: Server.StopServer,
        writeSynchronizer: WriteSynchronizer
    ): Boolean = {
      writeSynchronizer.synchronized {
        connectionData.serverToClient.write('\n'.toInt)
        connectionData.serverToClient.flush()
        true
      }
    }

    override protected def onStopServer(
        from: String,
        reason: String,
        exitCode: Int,
        connectionData: ConnectionData,
        data: Option[PreHandleConnectionData]
    ): Unit = {}
  }
}
