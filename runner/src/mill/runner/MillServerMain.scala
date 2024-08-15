package mill.runner

import sun.misc.{Signal, SignalHandler}

import java.io._
import java.net.Socket
import scala.jdk.CollectionConverters._
import org.newsclub.net.unix.AFUNIXServerSocket
import org.newsclub.net.unix.AFUNIXSocketAddress
import mill.main.client._
import mill.api.{SystemStreams, internal}
import mill.main.client.ProxyStream.Output
import mill.main.client.lock.{Lock, Locks}
import scala.util.Try

object MillServerMain{
  def main(args0: Array[String]): Unit = {
    // Disable SIGINT interrupt signal in the Mill server.
    //
    // This gets passed through from the client to server whenever the user
    // hits `Ctrl-C`, which by default kills the server, which defeats the purpose
    // of running a background server. Furthermore, the background server already
    // can detect when the Mill client goes away, which is necessary to handle
    // the case when a Mill client that did *not* spawn the server gets `CTRL-C`ed
    Signal.handle(
      new Signal("INT"),
      new SignalHandler() {
        def handle(sig: Signal) = {} // do nothing
      }
    )

    val acceptTimeoutMillis =
      Try(System.getProperty("mill.server_timeout").toInt).getOrElse(5 * 60 * 1000) // 5 minutes

    new MillServerMain(
      lockBase = os.Path(args0(0)),
      () => System.exit(Util.ExitServerCodeWhenIdle()),
      acceptTimeoutMillis = acceptTimeoutMillis,
      Locks.files(args0(0))
    ).run()
  }
}
class MillServerMain(
                      lockBase: os.Path,
                      interruptServer: () => Unit,
                      acceptTimeoutMillis: Int,
                      locks: Locks
                    )
  extends mill.main.server.Server[RunnerState](lockBase, interruptServer, acceptTimeoutMillis, locks) {
  def stateCache0 = RunnerState.empty


  def handleRun(clientSocket: Socket, initialSystemProperties: Map[String, String]): Unit = {

    val currentOutErr = clientSocket.getOutputStream
    try {
      val stdout = new PrintStream(new Output(currentOutErr, ProxyStream.OUT), true)
      val stderr = new PrintStream(new Output(currentOutErr, ProxyStream.ERR), true)

      // Proxy the input stream through a pair of Piped**putStream via a pumper,
      // as the `UnixDomainSocketInputStream` we get directly from the socket does
      // not properly implement `available(): Int` and thus messes up polling logic
      // that relies on that method
      val proxiedSocketInput = proxyInputStreamThroughPumper(clientSocket.getInputStream)

      val argStream = os.read.inputStream(lockBase / ServerFiles.runArgs)
      val interactive = argStream.read() != 0
      val clientMillVersion = Util.readString(argStream)
      val serverMillVersion = BuildInfo.millVersion
      if (clientMillVersion != serverMillVersion) {
        stderr.println(
          s"Mill version changed ($serverMillVersion -> $clientMillVersion), re-starting server"
        )
        os.write(
          lockBase / ServerFiles.exitCode,
          Util.ExitServerCodeWhenVersionMismatch().toString.getBytes()
        )
        System.exit(Util.ExitServerCodeWhenVersionMismatch())
      }
      val args = Util.parseArgs(argStream)
      val env = Util.parseMap(argStream)
      val userSpecifiedProperties = Util.parseMap(argStream)
      argStream.close()

      @volatile var done = false
      @volatile var idle = false
      val t = new Thread(
        () =>
          try {
            val streams = new SystemStreams(stdout, stderr, proxiedSocketInput)
            val (result, newStateCache) = try MillMain.main0(
              args,
              stateCache,
              interactive,
              streams,
              None,
              env.asScala.toMap,
              idle = _,
              userSpecifiedProperties.asScala.toMap,
              initialSystemProperties
            ) catch MillMain.handleMillException(streams.err, stateCache)


            stateCache = newStateCache
            os.write.over(
              lockBase / ServerFiles.exitCode,
              (if (result) 0 else 1).toString.getBytes()
            )
          } finally {
            done = true
            idle = true
          },
        "MillServerActionRunner"
      )
      t.start()
      // We cannot simply use Lock#await here, because the filesystem doesn't
      // realize the clientLock/serverLock are held by different threads in the
      // two processes and gives a spurious deadlock error
      while (!done && !locks.clientLock.probe()) Thread.sleep(3)

      if (!idle) interruptServer()

      t.interrupt()
      // Try to give thread a moment to stop before we kill it for real
      Thread.sleep(5)
      try t.stop()
      catch {
        case e: UnsupportedOperationException =>
        // nothing we can do about, removed in Java 20
        case e: java.lang.Error if e.getMessage.contains("Cleaner terminated abnormally") =>
        // ignore this error and do nothing; seems benign
      }

      // flush before closing the socket
      System.out.flush()
      System.err.flush()

    } finally ProxyStream.sendEnd(currentOutErr) // Send a termination
  }

}