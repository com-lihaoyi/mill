package mill.eval

import java.io.PrintStream
import java.nio.file.{Files, StandardOpenOption}

private class JsonArrayLogger[T: upickle.default.Writer](outPath: os.Path, indent: Int) {
  private var used = false

  private lazy val traceStream = {
    val options = Seq(
      Seq(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
      Seq(StandardOpenOption.TRUNCATE_EXISTING)
    ).flatten
    os.makeDir.all(outPath / os.up)
    new PrintStream(Files.newOutputStream(outPath.toNIO, options: _*))
  }

  def log(t: T) = synchronized {
    if (used) traceStream.println(",")
    else traceStream.println("[")
    used = true
    val indented = upickle.default.write(t, indent = indent)
      .linesIterator
      .map(" " * indent + _)
      .mkString("\n")

    traceStream.print(indented)
  }

  def close(): Unit = synchronized {
    traceStream.println()
    traceStream.println("]")
    traceStream.close()
  }
}

private class ProfileLogger(outPath: os.Path)
    extends JsonArrayLogger[ProfileLogger.Timing](outPath, indent = 2)

private object ProfileLogger {
  case class Timing(
      label: String,
      millis: Int,
      cached: java.lang.Boolean = null,
      dependencies: Seq[String] = Nil,
      inputsHash: Int,
      previousInputsHash: Int = -1
  )

  object Timing {
    implicit val readWrite: upickle.default.ReadWriter[Timing] = upickle.default.macroRW
  }
}

private class ChromeProfileLogger(outPath: os.Path)
    extends JsonArrayLogger[ChromeProfileLogger.TraceEvent](outPath, indent = -1) {

  def log(
      task: String,
      cat: String,
      startTime: Long,
      duration: Long,
      threadId: Int,
      cached: Boolean
  ): Unit = {

    val event = ChromeProfileLogger.TraceEvent(
      name = task,
      cat = cat,
      ph = "X",
      ts = startTime,
      dur = duration,
      pid = 1,
      tid = threadId,
      args = if (cached) Seq("cached") else Seq()
    )
    log(event)
  }
}

private object ChromeProfileLogger {

  /**
   * Trace Event Format, that can be loaded with Google Chrome via chrome://tracing
   * See https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/
   */
  case class TraceEvent(
      name: String,
      cat: String,
      ph: String,
      ts: Long,
      dur: Long,
      pid: Int,
      tid: Int,
      args: Seq[String]
  )

  object TraceEvent {
    implicit val readWrite: upickle.default.ReadWriter[TraceEvent] = upickle.default.macroRW
  }
}
