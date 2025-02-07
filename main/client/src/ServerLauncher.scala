package mill.main.client

import java.io.{InputStream, OutputStream, PrintStream}
import java.net.Socket
import java.nio.file.{Files, Path}
import scala.util.{Failure, Success, Try, Using}
import mill.main.client.lock.{Locks, TryLocked}
import scala.collection.JavaConverters.mapAsScalaMapConverter
import java.util.function.Supplier
import java.util.function.BooleanSupplier

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
    memoryLocks: Array[Locks],
    forceFailureForTestingMillisDelay: Int
) {

  final val serverProcessesLimit = 5
  final val serverInitWaitMillis = 10000

  def initServer(serverDir: Path, b: Boolean, locks: Locks): Unit

  def preRun(serverDir: Path): Unit

  def acquireLocksAndRun(serverDir0: Path): Result = {
    val setJnaNoSys: Boolean = System.getProperty("jna.nosys") == null
    if (setJnaNoSys) {
      System.setProperty("jna.nosys", "true")
    }

    var serverIndex: Int = 0
    while (serverIndex < serverProcessesLimit) {
      serverIndex += 1
      val serverDir: Path = serverDir0.getParent.resolve(s"${serverDir0.getFileName}-$serverIndex")

      Files.createDirectories(serverDir)

      val locks: Locks = if (memoryLocks != null) memoryLocks(serverIndex - 1) else Locks.files(serverDir.toString)
      try {
        val clientLocked: TryLocked = locks.clientLock.tryLock()
        if (clientLocked.isLocked) {
          preRun(serverDir)
          val exitCode = run(serverDir, setJnaNoSys, locks)
          val serverDir_ = serverDir
          return Result(exitCode, serverDir_)
        }
      } finally {
        locks.close()
      }
    }
    throw new ServerCouldNotBeStarted(s"Reached max server processes limit: $serverProcessesLimit")
  }

  def run(serverDir: Path, setJnaNoSys: Boolean, locks: Locks): Int = {
    val runArgsPath = serverDir.resolve(ServerFiles.runArgs)
    val socketPortPath = serverDir.resolve(ServerFiles.socketPort)
    val exitCodePath = serverDir.resolve(ServerFiles.exitCode)

    val f = Files.newOutputStream(runArgsPath)
    try {
      f.write(if (Util.hasConsole()) 1 else 0)
      Util.writeString(f, BuildInfo.millVersion)
      Util.writeArgs(args, f)
      Util.writeMap(env.asScala.toMap, f)
    } finally {
      f.close()
    }

    if (locks.processLock.probe()) initServer(serverDir, setJnaNoSys, locks)

    while (locks.processLock.probe()) Thread.sleep(1)

    val retryStart = System.currentTimeMillis()
    var ioSocket: Socket = null
    var socketThrowable: Throwable = null
    while (ioSocket == null && System.currentTimeMillis() - retryStart < serverInitWaitMillis) {
      try {
        val port = Files.readString(socketPortPath).toInt
        ioSocket = new Socket("127.0.0.1", port)
      } catch {
        case e: Exception =>
          socketThrowable = e
          Thread.sleep(1)
      }
    }

    if (ioSocket == null) {
      throw new Exception("Failed to connect to server", socketThrowable)
    }

    val outErr: InputStream = ioSocket.getInputStream
    val in: OutputStream = ioSocket.getOutputStream
    val outPumper = new ProxyStream.Pumper(outErr, stdout, stderr)
    val inPump = new InputPumper(
      new Supplier[InputStream] { def get(): InputStream = stdin },
      new Supplier[OutputStream] { def get(): OutputStream = in },
      true,
      new BooleanSupplier {
        override def getAsBoolean(): Boolean = true // or some condition
      }
    )
    val outPumperThread = new Thread(outPumper, "outPump")
    outPumperThread.setDaemon(true)
    val inThread = new Thread(inPump, "inPump")
    inThread.setDaemon(true)
    outPumperThread.start()
    inThread.start()

    if (forceFailureForTestingMillisDelay > 0) {
      Thread.sleep(forceFailureForTestingMillisDelay)
      throw new Exception(s"Force failure for testing: $serverDir")
    }
    outPumperThread.join()

    try {
      if (Files.exists(exitCodePath)) {
        Files.readAllLines(exitCodePath).get(0).toInt
      } else {
        System.err.println("mill-server/ exitCode file not found")
        1
      }
    } finally {
      ioSocket.close()
    }
  }
}

case class Result(exitCode: Int, serverDir: Path){
  def exitCode_() = exitCode
}