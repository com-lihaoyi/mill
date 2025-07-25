package mill.internal

import java.io.{BufferedOutputStream, PrintStream}
import java.nio.file.{Files, StandardOpenOption}
import java.util.concurrent.ArrayBlockingQueue

private[mill] class JsonArrayLogger[T: upickle.default.Writer](outPath: os.Path, indent: Int) {
  private var used = false

  val indentStr: String = " " * indent
  private lazy val traceStream = {
    val options = Seq(
      Seq(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
      Seq(StandardOpenOption.TRUNCATE_EXISTING)
    ).flatten
    os.makeDir.all(outPath / os.up)
    new PrintStream(new BufferedOutputStream(Files.newOutputStream(outPath.toNIO, options*)))
  }

  // Log the JSON entries asynchronously on a separate thread to try and avoid blocking
  // the main execution, but keep the size bounded so if the logging falls behind the
  // main thread will get blocked until logging can catch up
  val buffer = new ArrayBlockingQueue[T](100)
  val writeThread = new Thread(() =>
    while( {
      val value = try Some(buffer.take())
      catch{case e: InterruptedException => None}

      value match{
        case Some(v) =>
          if (used) traceStream.println(",")
          else traceStream.println("[")
          used = true
          val indented = upickle.default.write(v, indent = indent)
          .linesIterator
          .map(indentStr + _)
          .mkString("\n")

          traceStream.print(indented)
          true
        case None => false
      }
    })(),
    "JsonArrayLogger " + outPath.last
  )
  writeThread.start()

  def log(t: T): Unit = {
    buffer.offer(t)
  }

  def close(): Unit = synchronized {
    traceStream.println()
    traceStream.println("]")
    traceStream.close()
    writeThread.interrupt()
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

    def logBegin(
        terminal: String,
        cat: String,
        ph: String,
        startTime: Long,
        threadId: Int
    ): Unit = {

      val event = ChromeProfile.TraceEvent.Begin(
        name = terminal,
        cat = cat,
        ph = ph,
        ts = startTime,
        pid = 1,
        tid = threadId
      )
      log(event)
    }
    def logEnd(
        ph: String,
        endTime: Long,
        threadId: Int
    ): Unit = {

      val event = ChromeProfile.TraceEvent.End(
        ph = ph,
        ts = endTime,
        pid = 1,
        tid = threadId
      )
      log(event)
    }
  }

  private object ChromeProfile {

    /**
     * Trace Event Format, that can be loaded with Google Chrome via chrome://tracing
     * See https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/
     */
    enum TraceEvent derives upickle.default.ReadWriter {
      case Begin(
          name: String,
          cat: String,
          ph: String,
          ts: Long,
          pid: Int,
          tid: Int
      )

      case End(
          ph: String,
          ts: Long,
          pid: Int,
          tid: Int
      )
    }

  }
}
