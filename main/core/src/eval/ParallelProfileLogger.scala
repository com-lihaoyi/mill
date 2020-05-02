package mill.eval

import java.io.PrintStream
import java.nio.file.{Files, StandardOpenOption}


class ParallelProfileLogger(outPath: os.Path, startTime: Long) {
  private var used = false
  private val threadIds = collection.mutable.Map.empty[String, Int]
  lazy val traceStream = {
    val options = Seq(
      Seq(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
      Seq(StandardOpenOption.TRUNCATE_EXISTING)
    ).flatten
    os.makeDir.all(outPath)
    new PrintStream(Files.newOutputStream((outPath / "mill-par-profile.json").toNIO, options: _*))
  }

  def getThreadId(thread: String) = synchronized{
    threadIds.getOrElseUpdate(thread, threadIds.size)
  }
  def timeTrace(task: String,
                 cat: String,
                 startTime: Long,
                 endTime: Long,
                 thread: String,
                 cached: Boolean): Unit = synchronized{
    traceStream.synchronized {
      if(used) traceStream.println(",")
      else traceStream.println("[")
      used = true
      traceStream.print(
        upickle.default.write(
          TraceEvent(
            name = task,
            cat = cat,
            ph = "X",
            ts = startTime * 1000,
            dur = (endTime - startTime) * 1000 /*chrome treats the duration as microseconds*/,
            pid = 1,
            tid = getThreadId(thread),
            args = if (cached) Seq("cached") else Seq()
          )
        )
      )
    }
  }
  def close(): Unit = {
    traceStream.println("]")
    traceStream.close()
  }
}

/**
  * Trace Event Format, that can be loaded with Google Chrome via chrome://tracing
  * See https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/
  */
case class TraceEvent(name: String,
                      cat: String,
                      ph: String,
                      ts: Long,
                      dur: Long,
                      pid: Int,
                      tid: Int,
                      args: Seq[String])
object TraceEvent {
  implicit val readWrite: upickle.default.ReadWriter[TraceEvent] = upickle.default.macroRW
}
