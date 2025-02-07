package mill.main.client

import java.io.{InputStream, OutputStream, PrintStream}
import java.net.Socket
import java.nio.file.{Files, Path}
import scala.util.{Failure, Success, Try, Using}
import mill.main.client.lock.{Locks, TryLocked}
import scala.collection.JavaConverters.mapAsScalaMapConverter

/**
 * Client side code that interacts with Server.scala in order to launch a generic
 * long-lived background server.
 *
 * The protocol is as follows:
 *
 * - Client:
 *   - Take clientLock
 *   - If processLock is not yet taken, it means server is not running, so spawn a server
 *   - Wait for server socket to be available for connection
 * - Server:
 *   - Take processLock.
 *     - If already taken, it means another server was running
 *       (e.g. spawned by a different client) so exit immediately
 * - Server: loop:
 *   - Listen for incoming client requests on serverSocket
 *   - Execute client request
 *   - If clientLock is released during execution, terminate server (otherwise
 *     we have no safe way of terminating the in-process request, so the server
 *     may continue running for arbitrarily long with no client attached)
 *   - Send ProxyStream.END packet and call clientSocket.close()
 * - Client:
 *   - Wait for ProxyStream.END packet or clientSocket.close(),
 *     indicating server has finished execution and all data has been received
 */
abstract class ServerLauncher(
    stdin: InputStream,
    stdout: PrintStream,
    stderr: PrintStream,
    env: java.util.Map[String, String],
    args: Array[String],
    memoryLocks: Option[Array[Locks]],
    forceFailureForTestingMillisDelay: Int
) {

  final val serverProcessesLimit = 5
  final val serverInitWaitMillis = 10000

  def initServer(serverDir: Path, b: Boolean, locks: Locks): Unit

  def preRun(serverDir: Path): Unit



  def acquireLocksAndRun(serverDir0: Path): Result = {
    val setJnaNoSys = Option(System.getProperty("jna.nosys")).isEmpty
    if (setJnaNoSys) System.setProperty("jna.nosys", "true")

    (1 to serverProcessesLimit).foreach { serverIndex =>
      val serverDir = serverDir0.getParent.resolve(s"${serverDir0.getFileName}-$serverIndex")
      Files.createDirectories(serverDir)

      val result: Option[Result] = for {
        locks <- memoryLocks.map(_.lift(serverIndex - 1)).getOrElse(Some(Locks.files(serverDir.toString)))
        clientLocked <- Try(locks.clientLock.tryLock()).toOption
        if clientLocked.isLocked
      } yield {
        preRun(serverDir)
        Result(run(serverDir, setJnaNoSys, locks), serverDir)
      }

      result match {
        case Some(res) => res
        case _            => // continue to next index
      }
    }
    throw new ServerCouldNotBeStarted(s"Reached max server processes limit: $serverProcessesLimit")
  }

  def run(serverDir: Path, setJnaNoSys: Boolean, locks: Locks): Int = {
    val runArgsFile = serverDir.resolve(ServerFiles.runArgs)

    Using(Files.newOutputStream(runArgsFile)) { f =>
        f.write(if (Util.hasConsole()) 1 else 0)
        Util.writeString(f, BuildInfo.millVersion)
        Util.writeArgs(args, f)
        Util.writeMap(env.asScala.toMap, f)
    }.recover {
        case e: Exception =>
            // Handle the exception appropriately
            e.printStackTrace()
    }

    if (locks.processLock.probe()) initServer(serverDir, setJnaNoSys, locks)
    while (locks.processLock.probe()) Thread.sleep(1)

    val retryStart = System.currentTimeMillis()
    var ioSocket: Option[Socket] = None
    var socketThrowable: Option[Throwable] = None

    while (ioSocket.isEmpty && System.currentTimeMillis() - retryStart < serverInitWaitMillis) {
      Try {
        val port = Files.readString(serverDir.resolve(ServerFiles.socketPort)).toInt
        new Socket("127.0.0.1", port)
      } match {
        case Success(socket) => ioSocket = Some(socket)
        case Failure(e)      => socketThrowable = Some(e)
      }
      Thread.sleep(1)
    }

    ioSocket match {
      case Some(socket) =>
        val outPumper = new ProxyStream.Pumper(socket.getInputStream, stdout, stderr)
        val inPump = new InputPumper(() => stdin, () => socket.getOutputStream, true)
        val outPumperThread = new Thread(outPumper, "outPump")
        val inThread = new Thread(inPump, "inPump")
        outPumperThread.setDaemon(true)
        inThread.setDaemon(true)
        outPumperThread.start()
        inThread.start()

        if (forceFailureForTestingMillisDelay > 0) {
          Thread.sleep(forceFailureForTestingMillisDelay)
          throw new Exception(s"Force failure for testing: $serverDir")
        }

        outPumperThread.join()

        val exitCodeFile = serverDir.resolve(ServerFiles.exitCode)
        val exitCode =
          if (Files.exists(exitCodeFile)) Files.readAllLines(exitCodeFile).get(0).toInt
          else {
            System.err.println("mill-server/ exitCode file not found")
            1
          }

        socket.close()
        exitCode

      case None => throw new Exception("Failed to connect to server", socketThrowable.orNull)
    }
  }
}

case class Result(exitCode: Int, serverDir: Path){
  def exitCode_() = exitCode
}