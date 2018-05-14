package mill.scalalib

import java.util.Locale

import mill._
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
    Try(builder.start()) match {
      case Failure(t) =>
        T.ctx().log.error(t.getMessage)
        T.ctx().log.error(t.getStackTrace.map(_.toString).mkString("- ", s"${ammonite.util.Util.newLine}  ", ""))
        deletePidFile(pidFile)
      case Success(t) =>
        if (t.isAlive) {
          T.ctx().log.info(s"Successfully started '$t'")
          getPid(t) match {
            case Some(pid) =>
              T.ctx().log.info(s" with PID '$pid'")
              write.over(pidFile, pid.toString)
            //TODO: check after N seconds is process is still alive
            //  -> startup errors
            case None =>
              deletePidFile(pidFile)
          }
        } else {
          T.ctx().log.error(s"Started container '$t' but could not get PID")
          deletePidFile(pidFile)
        }
    }
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

  private def getPid(process: Process) =
    Try {
      if (process.getClass().getName().equals("java.lang.UNIXProcess")) {
        val field = process.getClass().getDeclaredField("pid")
        field.setAccessible(true)
        val pid = field.getLong(process)
        field.setAccessible(false)
        pid
      } else {
        throw new Exception("Not supported")
      }
    }
      .toOption

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
