package mill.internal

import java.io.{BufferedOutputStream, PrintStream}
import java.lang.management.ManagementFactory
import java.nio.file.{Files, StandardOpenOption}
import java.util.concurrent.ArrayBlockingQueue

class JsonArrayLogger[T: upickle.Writer](outPath: os.Path, indent: Int) {
  private var used = false

  @volatile var closed = false
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
  val buffer = new ArrayBlockingQueue[Option[T]](100)
  val writeThread = mill.api.daemon.StartThread("JsonArrayLogger " + outPath.last) {
    // Make sure all writes to `traceStream` are synchronized, as we
    // have two threads writing to it (one while active, one on close()
    traceStream.synchronized {
      while ({
        buffer.take() match {
          case Some(v) =>
            if (used) traceStream.println(",")
            else traceStream.println("[")
            used = true
            val indented = upickle.write(v, indent = indent)
              .linesIterator
              .map(indentStr + _)
              .mkString("\n")

            traceStream.print(indented)
            traceStream.flush()
            true
          case None => false
        }
      }) ()
    }
  }

  def log(t: T): Unit = synchronized {
    // Somehow in BSP mode we sometimes get logs coming in after close, just ignore them
    if (!closed) buffer.put(Some(t))
  }

  def close(): Unit = synchronized {
    closed = true
    buffer.put(None)
    // wait for background thread to clear out any buffered entries before shutting down
    writeThread.join()
    traceStream.synchronized {
      traceStream.flush()
      traceStream.println()
      traceStream.println("]")
      traceStream.close()
    }
  }
}

object JsonArrayLogger {

  class Profile(outPath: os.Path)
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
      implicit val readWrite: upickle.ReadWriter[Timing] = upickle.macroRW
    }
  }

  class ChromeProfile(outPath: os.Path)
      extends JsonArrayLogger[ChromeProfile.TraceEvent](outPath, indent = -1) {

    private val startTimeNanos = System.nanoTime()

    // Background thread for collecting system metrics every 1 second
    @volatile private var metricsThreadStopped = false
    val processorCount = Runtime.getRuntime.availableProcessors()
    private val metricsThread = mill.api.daemon.StartThread("chrome-profile-metrics-thread") {
      val osMXBean = ManagementFactory.getOperatingSystemMXBean
        .asInstanceOf[com.sun.management.OperatingSystemMXBean]

      while (!metricsThreadStopped && !closed) {
        try {
          val ts = (System.nanoTime() - startTimeNanos) / 1000 // microseconds

          // CPU load (0.0 to 1.0, or -1 if not available)
          val cpuLoad = osMXBean.getCpuLoad
          if (cpuLoad >= 0) {
            log(ChromeProfile.TraceEvent.Counter(
              name = "CPU Count",
              ts = ts,
              pid = 1,
              args = Map("CPUS" -> processorCount)
            ))
            log(ChromeProfile.TraceEvent.Counter(
              name = "CPU Load 0.0-1.0",
              ts = ts,
              pid = 1,
              args = Map("load" -> cpuLoad)
            ))
          }

          Thread.sleep(1000)
        } catch {
          case _: InterruptedException => // exit gracefully
        }
      }
    }

    def logBegin(
        terminal: String,
        cat: String,
        startTime: Long,
        threadId: Int
    ): Unit = {

      val event = ChromeProfile.TraceEvent.Begin(
        name = terminal,
        cat = cat,
        ts = startTime - startTimeNanos / 1000,
        pid = 1,
        tid = threadId
      )

      log(event)
    }
    def logEnd(
        endTime: Long,
        threadId: Int
    ): Unit = {

      val event = ChromeProfile.TraceEvent.End(
        ts = endTime - startTimeNanos / 1000,
        pid = 1,
        tid = threadId
      )
      log(event)
    }

    override def close(): Unit = {
      metricsThreadStopped = true
      metricsThread.interrupt()
      metricsThread.join()
      super.close()
    }
  }

  private object ChromeProfile {

    /**
     * Trace Event Format, that can be loaded with Google Chrome via chrome://tracing
     * See https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/
     */
    @upickle.implicits.key("ph")
    enum TraceEvent derives upickle.ReadWriter {
      @upickle.implicits.key("B") case Begin(
          name: String,
          cat: String,
          ts: Long,
          pid: Int,
          tid: Int
      )

      @upickle.implicits.key("E") case End(
          ts: Long,
          pid: Int,
          tid: Int
      )

      @upickle.implicits.key("C") case Counter(
          name: String,
          ts: Long,
          pid: Int,
          args: Map[String, Double]
      )
    }

  }
}
