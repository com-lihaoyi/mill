package mill.runner.client

import mill.main.client._
import mill.main.client.lock.Locks
import java.nio.file.Path
import scala.jdk.CollectionConverters._

object MillClientMain {
  def main(args: Array[String]): Unit = {
    var runNoServer = false

    if (args.nonEmpty) {
      val firstArg = args.head
      runNoServer = List(
        "--interactive",
        "--no-server",
        "--repl",
        "--bsp",
        "--help"
      ).contains(firstArg) || firstArg.startsWith("-i")
    }

    if (!runNoServer) {
      // WSL2 has the directory /run/WSL/ and WSL1 does not.
      val osVersion = System.getProperty("os.version")
      if (Option(osVersion).exists(v => v.contains("icrosoft") || v.contains("WSL"))) {
        // Server-Mode not supported under WSL1
        runNoServer = true
      }
    }

    if (runNoServer) {
      // Start in no-server mode
      MillNoServerLauncher.runMain(args)
    } else {
      try {
        // Start in client-server mode
        val optsArgs = Util.readOptsFileLines(MillProcessLauncher.millOptsFile).asScala
        optsArgs ++= args

        val launcher = new ServerLauncher(
          System.in,
          System.out,
          System.err,
          System.getenv().asScala.toMap.asJava,
          optsArgs.toArray,
          null,
          -1
        ) {
          override def initServer(serverDir: Path, setJnaNoSys: Boolean, locks: Locks): Unit = {
            MillProcessLauncher.launchMillServer(serverDir, setJnaNoSys)
          }

          override def preRun(serverDir: Path): Unit = {
            MillProcessLauncher.runTermInfoThread(serverDir)
          }
        }

        var exitCode = launcher.acquireLocksAndRun(OutFiles.out).exitCode
        if (exitCode == Util.ExitServerCodeWhenVersionMismatch()) {
          exitCode = launcher.acquireLocksAndRun(OutFiles.out).exitCode
        }
        System.exit(exitCode)
      } catch {
        case e: ServerCouldNotBeStarted =>
          System.err.println(
            s"""Could not start a Mill server process.
               |This could be caused by too many already running Mill instances
               |or by an unsupported platform.
               |${e.getMessage}
               |""".stripMargin
          )
          if (MillNoServerLauncher.load().canLoad) {
            System.err.println("Trying to run Mill in-process ...")
            MillNoServerLauncher.runMain(args)
          } else {
            System.err.println(
              """Loading Mill in-process isn't possible.
                |Please check your Mill installation!""".stripMargin
            )
            throw e
          }
      }
    }
  }
}
