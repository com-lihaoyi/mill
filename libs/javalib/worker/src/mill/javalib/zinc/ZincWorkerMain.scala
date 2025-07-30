package mill.javalib.zinc

import mill.client.lock.Locks
import mill.rpc.MillRpcWireTransport
import mill.server.Server

import java.io.{BufferedReader, InputStream, InputStreamReader, PrintStream}
import scala.util.Using
import scala.util.control.NonFatal

/** Entry point for the Zinc worker subprocess. */
object ZincWorkerMain {
  def main(args: Array[String]): Unit = {
    args match {
      case Array(daemonDir) =>
        val server: Server =
          new Server(os.Path(daemonDir), acceptTimeout = None, Locks.files(daemonDir)) {
            override protected type PreHandleConnectionData = Unit

            override protected def preHandleConnection(
                socketInfo: Server.SocketInfo,
                stdin: InputStream,
                stdout: PrintStream,
                stderr: PrintStream,
                stopServer: Server.StopServer,
                initialSystemProperties: Map[String, String]
            ): PreHandleConnectionData = ()

            override protected def handleConnection(
                socketInfo: Server.SocketInfo,
                stdin: InputStream,
                stdout: PrintStream,
                stderr: PrintStream,
                stopServer: Server.StopServer,
                setIdle: Server.SetIdle,
                initialSystemProperties: Map[String, String],
                data: PreHandleConnectionData
            ): Int = {
              val serverName = s"ZincWorkerTcpServer{${socketInfo.remote} -> ${socketInfo.local}}"
              Using.resource(BufferedReader(InputStreamReader(stdin))) { stdin =>
                val transport = MillRpcWireTransport.ViaStreams(serverName, stdin, stdout)
                try {
                  ZincWorkerRpcServer(serverName, transport, setIdle).run()
                  0
                } catch {
                  case NonFatal(err) =>
                    serverLog(s"$socketInfo failed: $err")
                    1
                }

              }
            }
          }
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
}
