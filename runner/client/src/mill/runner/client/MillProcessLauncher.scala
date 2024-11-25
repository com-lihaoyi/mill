package mill.runner.client

import java.io.{File, IOException, InputStream}
import java.nio.file.{Files, Path, Paths}
import java.util.{Properties, UUID}
import scala.jdk.CollectionConverters._
import mill.main.client.{EnvVars, ServerFiles, Util}
import java.util.Comparator
import mill.main.client.OutFiles

object MillProcessLauncher {

  def launchMillNoServer(args: Array[String]): Int = {
    val setJnaNoSys = Option(System.getProperty("jna.nosys")).isEmpty
    println("WANRING THIS IS NOT A RANDOM UUID")
    val sig = "3744873%08x" //    val sig = f"${UUID.randomUUID().hashCode}%08x"

    val processDir =
      Paths.get(".").resolve(OutFiles.out).resolve(OutFiles.millNoServer).resolve(sig)

    val command = millLaunchJvmCommand(setJnaNoSys) ++ Seq(
      "mill.runner.MillMain",
      processDir.toAbsolutePath.toString
    ) ++ Util.readOptsFileLines(millOptsFile).asScala ++ args

    val builder = new ProcessBuilder()
      .command(command.asJava)
      .inheritIO()

    var interrupted = false

    try {
      val process = configureRunMillProcess(builder, processDir)
      runTermInfoThread(processDir)
      process.waitFor()
    } catch {
      case e: InterruptedException =>
        interrupted = true
        throw e
    } finally {
      if (!interrupted) {
        // Cleanup
        Files.walk(processDir)
          .iterator()
          .asScala
          .toSeq
          .sortBy(_.toString.length)(Ordering.Int.reverse) // depth-first
          .foreach(p => p.toFile.delete())
      }
    }
  }

  def launchMillServer(serverDir: Path, setJnaNoSys: Boolean): Unit = {
    val command = millLaunchJvmCommand(setJnaNoSys) ++ Seq(
      "mill.runner.MillServerMain",
      serverDir.toFile.getCanonicalPath
    )

    val builder = new ProcessBuilder()
      .command(command.asJava)
      .redirectOutput(serverDir.resolve(ServerFiles.stdout).toFile)
      .redirectError(serverDir.resolve(ServerFiles.stderr).toFile)

    configureRunMillProcess(builder, serverDir)
  }

  def configureRunMillProcess(builder: ProcessBuilder, serverDir: Path): Process = {
    val sandbox = serverDir.resolve(ServerFiles.sandbox)
    Files.createDirectories(sandbox)
    builder.environment().put(EnvVars.MILL_WORKSPACE_ROOT, new File("").getCanonicalPath)
    builder.directory(sandbox.toFile)
    builder.start()
  }

  def millJvmOptsFile: File = {
    val millJvmOptsPath =
      Option(System.getenv(EnvVars.MILL_JVM_OPTS_PATH)).getOrElse(".mill-jvm-opts")
    new File(millJvmOptsPath).getAbsoluteFile
  }

  def millOptsFile: File = {
    val millOptsPath = Option(System.getenv(EnvVars.MILL_OPTS_PATH)).getOrElse(".mill-opts")
    new File(millOptsPath).getAbsoluteFile
  }

  def millJvmOptsAlreadyApplied: Boolean = {
    Option(System.getProperty("mill.jvm_opts_applied")).contains("true")
  }

  def millServerTimeout: Option[String] = Option(System.getenv(EnvVars.MILL_SERVER_TIMEOUT_MILLIS))

  def isWin: Boolean = System.getProperty("os.name", "").startsWith("Windows")

  def javaExe: String = {
    val javaHome = System.getProperty("java.home")
    if (Option(javaHome).exists(_.nonEmpty)) {
      val exePath = new File(
        javaHome + File.separator + "bin" + File.separator + "java" + (if (isWin) ".exe" else "")
      )
      if (exePath.exists()) {
        return exePath.getAbsolutePath
      }
    }
    "java"
  }

  def millClasspath: Array[String] = {
    val selfJars = Option(System.getProperty("MILL_CLASSPATH"))
      .orElse(Option(System.getenv("MILL_CLASSPATH")))
      .orElse(Option(System.getProperty("java.class.path")).map(_.replace(File.pathSeparator, ",")))
      .getOrElse(throw new RuntimeException("MILL_CLASSPATH is empty!"))

    selfJars.split(",").map(new File(_).getCanonicalPath)
  }

  def millLaunchJvmCommand(setJnaNoSys: Boolean): Seq[String] = {
    val vmOptions = scala.collection.mutable.Buffer[String]()
    vmOptions += javaExe

    if (setJnaNoSys) vmOptions += "-Djna.nosys=true"

    System.getProperties.asScala
      .filterKeys(_.startsWith("MILL_"))
      .filterNot(_._1 == "MILL_CLASSPATH")
      .foreach { case (key, value) => vmOptions += s"-D$key=$value" }

    millServerTimeout.foreach(timeout => vmOptions += s"-Dmill.server_timeout=$timeout")

    if (millJvmOptsFile.exists()) {
      vmOptions ++= Util.readOptsFileLines(millJvmOptsFile).asScala
    }

    vmOptions.toSeq ++ Seq("-cp", millClasspath.mkString(File.pathSeparator))
  }

  def getTerminalDim(dim: String, inheritError: Boolean): Int = {
    val process = new ProcessBuilder()
      .command("tput", dim)
      .redirectOutput(ProcessBuilder.Redirect.PIPE)
      .redirectError(if (inheritError) ProcessBuilder.Redirect.INHERIT
      else ProcessBuilder.Redirect.PIPE)
      .start()

    if (process.waitFor() != 0) throw new Exception("tput failed")
    new String(process.getInputStream.readAllBytes()).trim.toInt
  }

  def writeTerminalDims(tputExists: Boolean, serverDir: Path): Unit = {

    val dims =
      if (
        !tputExists
        // ||
        // System.console() == null
      ) "0 0"
      else
        try
          s"${getTerminalDim("cols", inheritError = true)} ${getTerminalDim("lines", inheritError = true)}"
        catch { case _: Exception => "0 0" }

    Files.write(serverDir.resolve(ServerFiles.terminfo), dims.getBytes)
  }

  def runTermInfoThread(serverDir: Path): Unit = {
    val termInfoThread = new Thread(
      () => {
        try {
          val tputExists =
            try {
              getTerminalDim("cols", inheritError = false)
              getTerminalDim("lines", inheritError = false)
              true
            } catch {
              case _: Exception => false
            }

          while (true) {
            writeTerminalDims(tputExists, serverDir)
            Thread.sleep(100)
          }
        } catch {
          case _: Exception =>
        }
      },
      "TermInfoPropagatorThread"
    )
    termInfoThread.start()
  }
}
