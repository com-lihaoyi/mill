package mill.main.client

import mill.main.client.OutFiles._
import mill.main.client.lock.{Locks, TryLocked}
import java.io.{InputStream, OutputStream, PrintStream}
import java.net.Socket
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._
import scala.util.Using

abstract class ServerLauncher(
    stdin: InputStream,
    stdout: PrintStream,
    stderr: PrintStream,
    env: Map[String, String],
    args: Array[String],
    memoryLocks: Option[Array[Locks]],
    forceFailureForTestingMillisDelay: Int
) {
  final val serverProcessesLimit = 5
  final val serverInitWaitMillis = 10000

  def initServer(serverDir: Path, setJnaNoSys: Boolean, locks: Locks): Unit

  def preRun(serverDir: Path): Unit

  def acquireLocksAndRun(outDir: String): Result = {
    val setJnaNoSys = Option(System.getProperty("jna.nosys")).isEmpty
    if (setJnaNoSys) System.setProperty("jna.nosys", "true")

    val versionAndJvmHomeEncoding =
      Util.sha1Hash(BuildInfo.millVersion + System.getProperty("java.home"))

    (1 to serverProcessesLimit).foreach { serverIndex =>
      val serverDir = Paths.get(outDir, millServer, s"$versionAndJvmHomeEncoding-$serverIndex")
      Files.createDirectories(serverDir)

      val locks = memoryLocks match {
        case Some(locksArray) => locksArray(serverIndex - 1)
        case None => Locks.files(serverDir.toString)
      }

      Using(locks.clientLock.tryLock()) { clientLocked =>
        if (clientLocked.isLocked) {
          preRun(serverDir)
          val exitCode = run(serverDir, setJnaNoSys, locks)
          return Result(exitCode, serverDir)
        }
      }
    }

    throw new ServerCouldNotBeStarted(s"Reached max server processes limit: $serverProcessesLimit")
  }

  private def run(serverDir: Path, setJnaNoSys: Boolean, locks: Locks): Int = {
    val serverPath = serverDir.resolve(ServerFiles.runArgs)

    Using.resource(Files.newOutputStream(serverPath)) { f =>
      f.write(
        // if (System.console() != null)
        // 1
        //   else
        0
      )
      Util.writeString(f, BuildInfo.millVersion)
      Util.writeArgs(args, f)
      Util.writeMap(env, f)
    }

    if (locks.processLock.probe()) {
      initServer(serverDir, setJnaNoSys, locks)
    }

    while (locks.processLock.probe()) Thread.sleep(3)

    val retryStart = System.currentTimeMillis()
    var ioSocket: Socket = null
    var socketThrowable: Throwable = null

    while (ioSocket == null && System.currentTimeMillis() - retryStart < serverInitWaitMillis) {
      try {
        val port = Files.readString(serverDir.resolve(ServerFiles.socketPort)).toInt
        ioSocket = new Socket("127.0.0.1", port)
      } catch {
        case e: Throwable =>
          socketThrowable = e
          Thread.sleep(10)
      }
    }

    if (ioSocket == null) throw new Exception("Failed to connect to server", socketThrowable)

    val outErr = ioSocket.getInputStream
    val in = ioSocket.getOutputStream
    val outPumper = new ProxyStream.Pumper(outErr, stdout, stderr)
    val inPumper = new InputPumper(() => stdin, () => in, true)

    val outPumperThread = new Thread(outPumper, "outPump")
    outPumperThread.setDaemon(true)

    val inThread = new Thread(inPumper, "inPump")
    inThread.setDaemon(true)

    outPumperThread.start()
    inThread.start()

    if (forceFailureForTestingMillisDelay > 0) {
      Thread.sleep(forceFailureForTestingMillisDelay)
      throw new Exception(s"Force failure for testing: $serverDir")
    }

    outPumperThread.join()

    try {
      val exitCodeFile = serverDir.resolve(ServerFiles.exitCode)
      Files.readAllLines(exitCodeFile).get(0).toInt
    } catch {
      case _: Throwable =>
        Util.ExitClientCodeCannotReadFromExitCodeFile()
    } finally {
      ioSocket.close()
    }
  }

  case class Result(exitCode: Int, serverDir: Path)
}
