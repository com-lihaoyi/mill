package mill.contrib.bsp

import java.io.{InputStream, PrintStream}

import ch.epfl.scala.bsp4j.{BuildClient, TaskId, TaskProgressParams}
import mill.api.{BspContext, Logger}
import mill.util.ProxyLogger

class MillBspLogger(client: BuildClient, taskId: Int, logger: Logger) extends ProxyLogger(logger) {

  override def ticker(s: String): Unit = {
    val progressString = s.split(" ")(0)
    val progress = progressString.substring(1, progressString.length - 1).split("/")
    val params = new TaskProgressParams(new TaskId(taskId.toString))
    params.setEventTime(System.currentTimeMillis())
    params.setMessage(s)
    params.setUnit(s.split(" ")(1))
    params.setProgress(progress(0).toLong)
    params.setTotal(progress(1).toLong)
    client.onBuildTaskProgress(params)
  }

}
