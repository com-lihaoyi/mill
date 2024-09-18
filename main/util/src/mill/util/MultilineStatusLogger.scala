package mill.util

import mill.api.SystemStreams

import java.io._

class MultilineStatusLogger(
    override val colored: Boolean,
    val enableTicker: Boolean,
    override val infoColor: fansi.Attrs,
    override val errorColor: fansi.Attrs,
    val systemStreams: SystemStreams,
    override val debugEnabled: Boolean,
) extends ColorLogger {
  import MultilineStatusLogger.Status
  val p = new PrintWriter(systemStreams.err)
  val nav = new AnsiNav(p)

  val current = collection.mutable.SortedMap.empty[Int, Seq[Status]]
  def currentPrompt: String = {
    val now = System.currentTimeMillis()
    current
      .map{case (threadId, statuses) =>
        val statusesString = statuses
          .map{status =>
            val runtimeSeconds = ((now - status.startTimeMillis) / 1000).toInt
            s"${runtimeSeconds}s ${status.text}"
          }.mkString(" / ")
        s"#$threadId $statusesString"
      }
      .mkString("\n")
  }
  def currentHeight: Int = currentPrompt.linesIterator.length

  def info(s: String): Unit = synchronized {
    nav.up(currentHeight)
    nav.left(9999)
    systemStreams.err.println(currentPrompt)
  }

  def error(s: String): Unit = synchronized {
    nav.up(currentHeight)
    nav.left(9999)
    systemStreams.err.println(currentPrompt)
  }

  def threadStatus(threadId: Int, text: String) = text match {
    case null => current(threadId) = current(threadId).init
    case _ => current(threadId) = current.getOrElse(threadId, Nil) :+ Status(System.currentTimeMillis(), text)
  }

  def ticker(s: String): Unit = ???


  def debug(s: String): Unit = synchronized {
    if (debugEnabled) {
      nav.up(currentHeight)
      nav.left(9999)
      systemStreams.err.println(currentPrompt)
    }
  }

  override def rawOutputStream: PrintStream = systemStreams.out
}

object MultilineStatusLogger {
  case class Status(startTimeMillis: Long, text: String)
}


