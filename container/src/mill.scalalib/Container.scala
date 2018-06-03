package mill.scalalib

import java.util.Locale

import mill._
import ammonite.ops.ImplicitWd._
import ammonite.ops._
import mill.define.Persistent
import mill.util.Logger

import scala.util.{Failure, Success, Try}
import collection.JavaConverters._


trait Containers extends JavaModule { outer =>
  object JettyContainer extends JettyContainer {
    override def packageWar: T[PathRef] = outer.packageWar
  }

  object TomactContainer extends TomcatContainer {
    override def packageWar: T[PathRef] = outer.packageWar
  }
}

trait JettyContainer extends Container {

  val containerBinaryInfo = VendorBinary.VendorBinaryInfo(
    "https://search.maven.org/remotecontent?filepath=org/eclipse/jetty/jetty-runner/9.4.8.v20171121/jetty-runner-9.4.8.v20171121.jar",
    "55573356efd6dc140a833f3de724a4a5a4ee4ff576217d3c3445d0e59d735d9b",
    RelPath("jetty-runner.jar"))

  def containerCommand = T {
    Seq("java",
      "-jar",
      containerBinary().path.toIO.getAbsolutePath,
      "--port",
      containerPort(),
      "--host",
      containerHost(),
      packageWar().path.toIO.getAbsolutePath)
  }
}


trait TomcatContainer extends Container {

  val containerBinaryInfo = VendorBinary.VendorBinaryInfo(
    "https://search.maven.org/remotecontent?filepath=com/github/jsimone/webapp-runner/8.0.51.0/webapp-runner-8.0.51.0.jar",
    "35395264d794dea0af8f4bb0fb2292d6e31679e95e0d52a7e5e2806629f33880",
    RelPath("webapp-runner.jar"))

  def containerCommand = T {
    Seq("java",
      "-jar",
      containerBinary().path.toIO.getAbsolutePath,
      "--port",
      containerPort(),
      s"-Aaddress=${containerHost()}",
      packageWar().path.toIO.getAbsolutePath)
  }
}


trait Container extends mill.Module {

  val os = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH)
  val isWindows = os.contains("win")

  val containerBinaryInfo: VendorBinary.VendorBinaryInfo

  def containerCommand: T[Seq[String]]

  def packageWar: T[PathRef]

  def containerPort = T {
    "8080"
  }

  def containerHost = T {
    "localhost"
  }

  def containerStop() = T.command {
    stopContainerInternal(containerPidFile(), T.ctx().log)
  }

  def containerStart = T {
    // State
    val pidFile = containerPidFile()
    val command = containerCommand()
    val artifact = packageWar() // dummy dep to trigger on code changes
    // logic
    stopContainerInternal(pidFile, T.ctx().log)
    T.ctx().log.info(s"State: $pidFile, $command")

    T.ctx().log.info("Starting container")
    val builder = containerProcess(command, T.ctx().env)
    val pid = startProcess(builder, pidFile, T.ctx().log)
    PathRef(pidFile)
  }

  def containerBinary: Persistent[PathRef] = T.persistent {
    VendorBinary.vendorBinary(containerBinaryInfo, T.ctx().dest, T.ctx().log)
  }

  def containerPidFile = T.persistent {
    T.ctx.dest / "containerPidFile"
  }

  def stopContainerInternal(pidFile: Path, log: Logger) = {
    log.error("stop internal ")
    loadPidFile(pidFile) match {
      case None =>
        log.error(s"problem loading pidfile")
      case Some(pid) if !pid.isEmpty =>
        log.error(s"stopping process '$pid'")
        val os = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH)
        if (os.contains("win")) {
          // figure out how to kill processes on windows
        } else {
          // assume ammonite kill works
          Try {
            //TODO: be nicer to our processes
            //TODO: sanity check pid string
            kill(9)(pwd) ! pid.toInt
          }
        }
        deletePidFile(pidFile)
      case Some(_) =>
    }
  }


  private def loadPidFile(pidFile: Path) = {
    Try {
      read ! pidFile
    }
      .toOption
      .map(_.trim)
  }

  private def deletePidFile(pidFile: Path) = {
    rm ! pidFile
  }

  private def startProcess(processBuilder: ProcessBuilder, pidFile: Path, log: Logger): Option[Long] = {
    val oldProcessList = if (isWindows) {
      %%("jps")
        .out
        .lines
        .filter(!_.contains("Jps"))
        .map(line => Try { line.trim.split(" ").apply(0).toLong }.toOption)
        .flatten.toSet
    } else {
      val empty: Set[Long] = Set()
      empty
    }
    Try(processBuilder.start()) match {
      case Failure(t) =>
        log.error(t.getMessage)
        log.error(t.getStackTrace.map(_.toString).mkString("- ", s"${ammonite.util.Util.newLine}  ", ""))
        deletePidFile(pidFile)
        None
      case Success(process) =>
        if (process.isAlive) {
          log.info(s"Successfully started '$process'")
          val pid: Option[Long] = Try {
            if (process.getClass().getName().equals("java.lang.UNIXProcess")) {
              getUnixPid(process)
            } else if (isWindows) {
              getWindowsPid(oldProcessList)
            } else {
              throw new Exception()
            }
          }.toOption
          pid match {
            case Some(pid) =>
              log.info(s" with PID '$pid'")
              write.over(pidFile, pid.toString)
              //TODO: check after N seconds is process is still alive
              //  -> startup errors
              Some(pid)
            case None =>
              deletePidFile(pidFile)
              None
          }
        } else {
          log.error(s"Started container '$process' but could not get PID")
          deletePidFile(pidFile)
          None
        }
    }
  }

  private def getUnixPid(process: Process): Long = {
    val field = process.getClass().getDeclaredField("pid")
    field.setAccessible(true)
    val pid = field.getLong(process)
    field.setAccessible(false)
    pid
  }

  private def getWindowsPid(oldProcessList: scala.collection.immutable.Set[Long]): Long = {
    val newProcessList = %%("jps")
      .out
      .lines
      .filter(!_.contains("Jps"))
      .map(line => Try { line.trim.split(" ").apply(0).toLong }.toOption)
      .flatten.toSet
    newProcessList.diff(oldProcessList).toSeq.head
  }

  private def containerProcess(command: Seq[String], env: Map[String, String]) = {
    val builder = new java.lang.ProcessBuilder()
    for ((k, v) <- env) {
      if (v != null) {
        builder.environment().put(k, v)
      } else {
        builder.environment().remove(k)
      }
    }
    builder
      .directory(pwd.toIO)
      .command(command.asJava)
      .inheritIO()
  }

}
