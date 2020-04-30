package eval

import java.io.PrintStream
import java.nio.file.{Files, StandardOpenOption}

import mill.eval.Evaluator

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
          Evaluator.TraceEvent(
            name = task,
            cat = cat,
            ph = "X",
            ts = startTime * 1000,
            dur = (endTime - startTime) * 1000 /*chrome treats the duration as microseconds*/,
            pid = 1,
            tid = threadIds.getOrElseUpdate(thread, threadIds.size),
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
