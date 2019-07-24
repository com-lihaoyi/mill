package mill.contrib.bsp

import ch.epfl.scala.bsp4j._
import mill.api.Logger
import mill.util.ProxyLogger

class MillBspLogger(client: BuildClient, taskId: Int, logger: Logger) extends ProxyLogger(logger) {

  override def ticker(s: String): Unit = {
    try {
      val progressString = s.split(" ")(0)
      val progress = progressString.substring(1, progressString.length - 1).split("/")
      val params = new TaskProgressParams(new TaskId(taskId.toString))
      params.setEventTime(System.currentTimeMillis())
      params.setMessage(s)
      params.setUnit(s.split(" ")(1))
      params.setProgress(progress(0).toLong)
      params.setTotal(progress(1).toLong)
      client.onBuildTaskProgress(params)
    } catch {
      case e: Exception =>
    }
  }

  override def error(s: String): Unit = {
    super.error(s)
    client.onBuildShowMessage(new ShowMessageParams(MessageType.ERROR, s))
  }

  override def info(s: String): Unit = {
    super.info(s)
    client.onBuildShowMessage(new ShowMessageParams(MessageType.INFORMATION, s))
  }

  override def debug(s: String): Unit = {
    super.debug(s)
    client.onBuildShowMessage(new ShowMessageParams(MessageType.LOG, s))
  }

}
