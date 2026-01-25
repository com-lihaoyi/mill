package mill.javalib.worker

import mill.api.SystemStreamsUtils
import mill.api.daemon.{DummyInputStream, SystemStreams}
import mill.client.lock.Locks
import mill.javalib.zinc.ZincWorker
import mill.javalib.worker.JvmWorkerRpcServer
import mill.rpc.MillRpcWireTransport
import mill.server.Server
import mill.server.Server.ConnectionData
import pprint.{TPrint, TPrintColors}

import java.io.{BufferedReader, IOException, InputStreamReader, PrintStream}
import scala.util.Using

/** Entry point for the Zinc worker subprocess. */
object MillJvmWorkerMain {
  def main(args: Array[String]): Unit = SystemStreamsUtils.withTopLevelSystemStreamProxy {
    args match {
      case Array(daemonDir, jobsStr, useFileLocksStr) =>
        val useFileLocks = useFileLocksStr == "true"
        val server = JvmWorkerTcpServer(os.Path(daemonDir), jobsStr.toInt, useFileLocks)
        server.run()
        // Make sure we explicitly exit, so that even if there are some leaked threads
        // hanging around the process properly terminates rather than hanging
        sys.exit(0)

      case other =>
        Console.err.println(
          s"""Usage: jvm-worker <daemonDir> <jobs> <useFileLocks>
             |
             |Given: ${other.mkString(" ")}
             |""".stripMargin
        )
        sys.exit(1)
    }
  }

  private case class JvmWorkerServerData(rpcTransport: MillRpcWireTransport)

  private class JvmWorkerTcpServer(daemonDir: os.Path, jobs: Int, useFileLocks: Boolean)
      extends Server[JvmWorkerServerData, Unit](Server.Args(
        daemonDir,
        acceptTimeout = None, // The worker kills the process when it needs to.
        Locks.forDirectory(daemonDir.toString, useFileLocks),
        bufferSize = 4 * 1024
      )) {
    private val className = summon[TPrint[JvmWorkerTcpServer]].render(using TPrintColors.Colors)

    /**
     * Shared instance of the Zinc worker.
     *
     * It is very important that the same instance is used in all connections as it contains the necessary caches
     * to make Scala compilation fast!
     */
    private val worker = ZincWorker(jobs = jobs, useFileLocks = useFileLocks)

    override def prepareConnection(
        connectionData: ConnectionData,
        stopServer: Server.StopServer0[Unit]
    ): JvmWorkerServerData = {
      val serverName = s"$className{${connectionData.socketName}}"
      val transport = MillRpcWireTransport(
        name = serverName,
        serverToClient = new BufferedReader(new InputStreamReader(connectionData.clientToServer)),
        clientToServer = new PrintStream(connectionData.serverToClient, true),
        writeSynchronizer = new Object
      )
      JvmWorkerServerData(transport)
    }

    override def handleConnection(
        connectionData: ConnectionData,
        stopServer: Server.StopServer0[Unit],
        setIdle: Server.SetIdle,
        data: JvmWorkerServerData
    ): Unit = {
      val transport = data.rpcTransport
      val serverName = transport.name
      val server = JvmWorkerRpcServer(worker, serverName, transport, setIdle, serverLog)

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
    }

    override def endConnection(
        connectionData: ConnectionData,
        data: Option[JvmWorkerServerData],
        result: Option[Unit]
    ): Unit = {
      // Close the transport to release resources
      data.foreach { d =>
        try d.rpcTransport.close()
        catch { case _: Exception => }
      }
    }

    def systemExit(exitCode: Unit): Nothing = ???

    def exitCodeServerTerminated: Unit = ()

    override def checkIfClientAlive(
        connectionData: ConnectionData,
        data: JvmWorkerServerData
    ): Boolean = {
      // Use the transport's synchronized write to avoid race conditions with RPC messages
      try {
        data.rpcTransport.write("")
        true
      } catch {
        case _: IOException => false
      }
    }
  }
}
