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
) extends ColorLogger with AutoCloseable{
  val startTimeMillis = System.currentTimeMillis()
  import MultilineStatusLogger._

  val systemStreams = new SystemStreams(
    new PrintStream(new BufferedOutputStream(new StateStream(systemStreams0.out))),
    new PrintStream(new BufferedOutputStream(new StateStream(systemStreams0.err))),
    systemStreams0.in
  )

  override def close() = running = false
  @volatile var running = true
  @volatile var dirty = false

  val secondsTickerThread = new Thread(new Runnable{
    def run(): Unit = {
      while(running) {
        Thread.sleep(1000)
        synchronized{
          writeAndUpdatePrompt(systemStreams0.err) {/*donothing*/}
        }
      }
    }
  })

  secondsTickerThread.start()
  val bufferFlusherThread = new Thread(new Runnable{
    def run(): Unit = {
      while(running) {
        Thread.sleep(10)
        synchronized{
          systemStreams.err.flush()
          systemStreams.out.flush()
        }
      }
    }
  })

  bufferFlusherThread.start()

  def renderSeconds(millis: Long) =  (millis / 1000).toInt match{
    case 0 => ""
    case n => s" ${n}s"
  }
  val current = collection.mutable.SortedMap.empty[Int, Seq[Status]]
  def currentPrompt: Seq[String] = {
    val now = System.currentTimeMillis()
    List("="*80 + renderSeconds(now - startTimeMillis)) ++
    current
      .collect{case (threadId, statuses) if statuses.nonEmpty =>
        val statusesString = statuses
          .map{status => status.text + renderSeconds(now - status.startTimeMillis)}
          .mkString(" / ")
        statusesString
      }
      .toList
  }
  def currentHeight: Int = currentPrompt.length
  private def log0(s: String) = {
    systemStreams.err.println(s)
  }

  def info(s: String): Unit = synchronized { log0(s); systemStreams0.err.flush() }

  def error(s: String): Unit = synchronized { log0(s); systemStreams0.err.flush() }

  def ticker(s: String): Unit = synchronized {

    val threadId = Thread.currentThread().getId.toInt
    writeAndUpdatePrompt(systemStreams0.err) {
      if (s.contains("<END>")) current(threadId) = current(threadId).init
      else current(threadId) = current.getOrElse(threadId, Nil) :+ Status(System.currentTimeMillis(), s)
    }
  }


  def debug(s: String): Unit = synchronized {
    if (debugEnabled) log0(s)
  }


  override def rawOutputStream: PrintStream = systemStreams0.out


  private def writeAndUpdatePrompt[T](wrapped: PrintStream)(t: => T): T = {
    AnsiNav(wrapped).left(9999)
    AnsiNav(wrapped).clearScreen(0)
    val res = t
    for(line <- currentPrompt) wrapped.println(line)
    AnsiNav(wrapped).up(currentHeight)
    wrapped.flush()
    res
  }

  class StateStream(wrapped: PrintStream) extends OutputStream {

    override def write(b: Array[Byte]): Unit = synchronized {
      if (b.last == '\n') writeAndUpdatePrompt(wrapped)(write(b))
      else write(b)
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
      if (b(off + len - 1) == '\n') writeAndUpdatePrompt(wrapped)(wrapped.write(b, off, len))
      else wrapped.write(b, off, len)
    }

    override def write(b: Int): Unit = synchronized {
      if (b == '\n') writeAndUpdatePrompt(wrapped)(wrapped.write(b))
      else wrapped.write(b)
    }

    override def flush(): Unit = synchronized {
      wrapped.flush()
    }
  }
}

object MultilineStatusLogger {
  case class Status(startTimeMillis: Long, text: String)
}
