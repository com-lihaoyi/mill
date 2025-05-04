package mill.exec

import java.io.PrintStream
import java.nio.file.{Files, StandardOpenOption}

private[mill] class JsonArrayLogger[T: upickle.default.Writer](outPath: os.Path, indent: Int) {
  private var used = false

  val indentStr: String = " " * indent
  private lazy val traceStream = {
    val options = Seq(
      Seq(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
      Seq(StandardOpenOption.TRUNCATE_EXISTING)
    ).flatten
    os.makeDir.all(outPath / os.up)
    new PrintStream(Files.newOutputStream(outPath.toNIO, options*))
  }

  def log(t: T): Unit = synchronized {
    if (used) traceStream.println(",")
    else traceStream.println("[")
    used = true
    val indented = upickle.default.write(t, indent = indent)
      .linesIterator
      .map(indentStr + _)
      .mkString("\n")

    traceStream.print(indented)
  }

  def close(): Unit = synchronized {
    traceStream.println()
    traceStream.println("]")
    traceStream.close()
  }
}

private[mill] object JsonArrayLogger {

  private[mill] class Profile(outPath: os.Path)
      extends JsonArrayLogger[Profile.Timing](outPath, indent = 2) {
    def log(
        terminal: String,
        duration: Long,
        cached: java.lang.Boolean,
        valueHashChanged: java.lang.Boolean,
        deps: Seq[String],
        inputsHash: Int,
        previousInputsHash: Int
    ): Unit = {
      log(
        Profile.Timing(
          terminal,
          (duration / 1000).toInt,
          cached,
          valueHashChanged,
          deps,
          inputsHash,
          previousInputsHash
        )
      )
    }
  }

  private object Profile {
    case class Timing(
        label: String,
        millis: Int,
        cached: java.lang.Boolean = null,
        valueHashChanged: java.lang.Boolean = null,
        dependencies: Seq[String] = Nil,
        inputsHash: Int,
        previousInputsHash: Int = -1
    )

    object Timing {
      implicit val readWrite: upickle.default.ReadWriter[Timing] = upickle.default.macroRW
    }
  }

  private[mill] class ChromeProfile(outPath: os.Path)
      extends JsonArrayLogger[ChromeProfile.TraceEvent](outPath, indent = -1) {

    def log(
        terminal: String,
        cat: String,
        startTime: Long,
        duration: Long,
        threadId: Int,
        cached: Boolean
    ): Unit = {

      val event = ChromeProfile.TraceEvent(
        name = terminal,
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

  private object ChromeProfile {

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
}
