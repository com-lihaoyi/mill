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
  def currentPrompt: Seq[String] = {
    val now = System.currentTimeMillis()
    current
      .collect{case (threadId, statuses) if statuses.nonEmpty =>
        val statusesString = statuses
          .map{status =>
            val runtimeSeconds = ((now - status.startTimeMillis) / 1000).toInt
            s"${runtimeSeconds}s ${status.text}"
          }.mkString(" / ")
        s"| $statusesString"
      }
      .toList
  }
  def currentHeight: Int = currentPrompt.length

  private def updateUI() = {
//    println("-"*80 + currentHeight)
    nav.up(currentHeight)
    nav.left(9999)
    p.flush()
  }
  private def log0(s: String) = {
    updateUI()
    printClear(s +: currentPrompt)
  }
  private def printClear(lines: Seq[String]) = {
    for(line <- lines){
      nav.clearLine(0)
      p.println(line)
    }
  }

  def info(s: String): Unit = synchronized { log0(s); p.flush() }

  def error(s: String): Unit = synchronized { log0(s); p.flush() }

  def ticker(s: String): Unit = synchronized {
    updateUI()
    val prevHeight = currentHeight
    val threadId = Thread.currentThread().getId.toInt
    if (s.contains("<END>")) current(threadId) = current(threadId).init
    else current(threadId) = current.getOrElse(threadId, Nil) :+ Status(System.currentTimeMillis(), s)

    val verticalSpacerHeight = prevHeight - currentHeight
    printClear(currentPrompt)
    if (verticalSpacerHeight > 0){
      val verticalSpacer = Seq.fill(verticalSpacerHeight)("")
      printClear(verticalSpacer)
      nav.up(verticalSpacerHeight)
    }
    p.flush()
  }


  def debug(s: String): Unit = synchronized {
    if (debugEnabled) log0(s)
  }

  override def rawOutputStream: PrintStream = systemStreams.out
}

object MultilineStatusLogger {
  case class Status(startTimeMillis: Long, text: String)
}


