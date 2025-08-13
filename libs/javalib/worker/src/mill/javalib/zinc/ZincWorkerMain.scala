package mill.javalib.zinc

import mill.api.daemon.{DummyInputStream, SystemStreams}
import mill.api.SystemStreamsUtils
import mill.client.lock.Locks
import mill.rpc.MillRpcWireTransport
import mill.server.Server
import pprint.{TPrint, TPrintColors}

import java.io.{BufferedInputStream, BufferedReader, InputStreamReader, PrintStream}
import scala.util.Using
import scala.util.control.NonFatal

/** Entry point for the Zinc worker subprocess. */
object ZincWorkerMain {
  def main(args: Array[String]): Unit = SystemStreamsUtils.withTopLevelSystemStreamProxy {
    args match {
      case Array(daemonDir) =>
        val server = ZincWorkerTcpServer(os.Path(daemonDir))
        server.run()

      case other =>
        Console.err.println(
          s"""Usage: zinc-worker <daemonDir>
             |
             |Given: ${other.mkString(" ")}
             |""".stripMargin
        )
        sys.exit(1)
    }
  }

  private class ZincWorkerTcpServer(daemonDir: os.Path) extends Server(
        daemonDir,
        // The worker kills the process when it needs to.
        acceptTimeout = None,
        Locks.files(daemonDir.toString)
      ) {
    private val className = summon[TPrint[ZincWorkerTcpServer]].render(using TPrintColors.Colors)

    override protected type PreHandleConnectionData = Unit

    override protected def preHandleConnection(
        socketInfo: Server.SocketInfo,
        stdin: BufferedInputStream,
        stdout: PrintStream,
        stderr: PrintStream,
        stopServer: Server.StopServer,
        initialSystemProperties: Map[String, String]
    ): PreHandleConnectionData = ()

    override protected def handleConnection(
        socketInfo: Server.SocketInfo,
        stdin: BufferedInputStream,
        stdout: PrintStream,
        stderr: PrintStream,
        stopServer: Server.StopServer,
        setIdle: Server.SetIdle,
        initialSystemProperties: Map[String, String],
        data: PreHandleConnectionData
    ) = {
      val serverName = s"$className{${socketInfo.remote} -> ${socketInfo.local}}"
      Using.resource(BufferedReader(InputStreamReader(stdin))) { stdin =>
        val transport = MillRpcWireTransport.ViaStreams(serverName, stdin, stdout)
        try {
          val server = ZincWorkerRpcServer(serverName, transport, setIdle, serverLog)

          // Make sure stdout and stderr is sent to the client
          SystemStreamsUtils.withStreams(SystemStreams(
            out = PrintStream(server.clientStdout.asStream),
            err = PrintStream(server.clientStderr.asStream),
            in = DummyInputStream
          )) {
            serverLog("server.run() starting")
            server.run()
            serverLog("server.run() finished")
            0
          }
        } catch {
          case NonFatal(err) =>
            serverLog(s"$socketInfo failed: $err")
            1
        }
      }
    }
  }
}
