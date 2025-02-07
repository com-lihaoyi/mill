package mill.main.client

import java.io.{BufferedReader, File, FileReader, FileNotFoundException, IOException, PrintStream}
import scala.util.{Failure, Success, Try}

class FileToStreamTailer(file: File, stream: PrintStream, intervalMsec: Int) extends Thread("Tail") with AutoCloseable {

  private val interval = intervalMsec
  @volatile private var keepReading = true
  @volatile private var flushy = false
  private var ignoreHead = true

  setDaemon(true)

  override def run(): Unit = {
    if (isInterrupted) keepReading = false
    var reader: Option[BufferedReader] = None

    try {
      while (keepReading || flushy) {
        flushy = false
        try {
          // Init reader if not already done
          if (reader.isEmpty) {
            Try(new BufferedReader(new FileReader(file))) match {
              case Success(r)                        => reader = Some(r)
              case Failure(_: FileNotFoundException) => ignoreHead = false
              case Failure(_)                        => // handle other exceptions
            }
          }

          reader.foreach { r =>
            try {
              var line: String = null
              while ({ line = r.readLine(); line != null }) {
                if (!ignoreHead) {
                  stream.println(line)
                }
              }
              // After reading once, stop ignoring the head
              ignoreHead = false
            } catch {
              case _: IOException => // could not read or file vanished
            }
          }
        } finally {
          if (keepReading) {
            // Wait before the next read
            try {
              Thread.sleep(interval)
            } catch {
              case _: InterruptedException => // can't handle anyway
            }
          }
        }
      }
    } finally {
      reader.foreach { r =>
        try {
          r.close()
        } catch {
          case _: IOException => // nothing to do if failed to close
        }
      }
    }
  }

  override def interrupt(): Unit = {
    keepReading = false
    super.interrupt()
  }

  /** Force a next read, even if we interrupt the thread.
    */
  def flush(): Unit = {
    flushy = true
  }

  override def close(): Unit = {
    flush()
    interrupt()
  }
}
