package mill.util

import mill.api.SystemStreams

import java.io._

class MultilineStatusLogger(
    override val colored: Boolean,
    val enableTicker: Boolean,
    override val infoColor: fansi.Attrs,
    override val errorColor: fansi.Attrs,
    systemStreams0: SystemStreams,
    override val debugEnabled: Boolean,
) extends ColorLogger {
  import MultilineStatusLogger._
  AnsiNav(systemStreams0.err).saveCursor()
  val systemStreams = wrapSystemStreams(systemStreams0)

  val current = collection.mutable.SortedMap.empty[Int, Seq[Status]]
  def currentPrompt: Seq[String] = {
    val now = System.currentTimeMillis()
    List("="*80) ++
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

  private def log0(s: String) = {
    systemStreams.err.println(s)
  }

  private def printClear(nav: AnsiNav, lines: Seq[String]) = {
    for(line <- lines) nav.output.println(line)
    nav.output.flush()
  }

  def info(s: String): Unit = synchronized { log0(s); systemStreams0.err.flush() }

  def error(s: String): Unit = synchronized { log0(s); systemStreams0.err.flush() }

  def ticker(s: String): Unit = synchronized {

    val threadId = Thread.currentThread().getId.toInt

    if (s.contains("<END>")) current(threadId) = current(threadId).init
    else current(threadId) = current.getOrElse(threadId, Nil) :+ Status(System.currentTimeMillis(), s)

    systemStreams.err.write(Array[Byte]())
  }


  def debug(s: String): Unit = synchronized {
    if (debugEnabled) log0(s)
  }


  override def rawOutputStream: PrintStream = systemStreams0.out
  def wrapSystemStreams(systemStreams0: SystemStreams): SystemStreams = {
    new SystemStreams(
      new PrintStream(new StateStream(systemStreams0.out)),
      new PrintStream(new StateStream(systemStreams0.err)),
      systemStreams0.in
    )
  }

  class StateStream(wrapped: PrintStream) extends OutputStream {

    def wrapWrite[T](t: => T): T = {
      AnsiNav(wrapped).restoreCursor()
      AnsiNav(wrapped).saveCursor()
      AnsiNav(wrapped).down(1)
      AnsiNav(wrapped).clearScreen(0)
      AnsiNav(wrapped).restoreCursor()
      val res = t
      AnsiNav(wrapped).saveCursor()
      AnsiNav(wrapped).down(1)
      printClear(AnsiNav(wrapped), currentPrompt)
      AnsiNav(wrapped).restoreCursor()
      AnsiNav(wrapped).saveCursor()
      flush()
      res
    }

    override def write(b: Array[Byte]): Unit = synchronized {
      wrapWrite(write(b))
      flush()
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
      wrapWrite(wrapped.write(b, off, len))
      flush()
    }

    override def write(b: Int): Unit = synchronized {
      wrapWrite(wrapped.write(b))
      flush()
    }

    override def flush(): Unit = synchronized {
      wrapped.flush()
    }
  }
}

object MultilineStatusLogger {
  case class Status(startTimeMillis: Long, text: String)
}


