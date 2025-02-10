package mill.runner

import sun.misc.{Signal, SignalHandler}
import mill.api.SystemStreams
import mill.client.Util
import mill.client.lock.Locks

import scala.util.Try

object MillServerMain {
  def main(args0: Array[String]): Unit = SystemStreams.withTopLevelSystemStreamProxy {
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
      Try(System.getProperty("mill.server_timeout").toInt).getOrElse(30 * 60 * 1000) // 30 minutes

    new MillServerMain(
      serverDir = os.Path(args0(0)),
      acceptTimeoutMillis = acceptTimeoutMillis,
      Locks.files(args0(0))
    ).run()
  }
}
class MillServerMain(
    serverDir: os.Path,
    acceptTimeoutMillis: Int,
    locks: Locks
) extends mill.main.server.Server[RunnerState](
      serverDir,
      acceptTimeoutMillis,
      locks
    ) {

  override def exitServer(): Unit = {
    super.exitServer(); System.exit(Util.ExitServerCodeWhenIdle())
  }
  def stateCache0 = RunnerState.empty

  def main0(
      args: Array[String],
      stateCache: RunnerState,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Int => Nothing
  ): (Boolean, RunnerState) = {
    try MillMain.main0(
        args = args,
        stateCache = stateCache,
        mainInteractive = mainInteractive,
        streams0 = streams,
        bspLog = None,
        env = env,
        setIdle = setIdle,
        userSpecifiedProperties0 = userSpecifiedProperties,
        initialSystemProperties = initialSystemProperties,
        systemExit = systemExit,
        serverDir = serverDir
      )
    catch MillMain.handleMillException(streams.err, stateCache)
  }
}
