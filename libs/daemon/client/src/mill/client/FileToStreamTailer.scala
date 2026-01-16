package mill.client

import java.io.{BufferedReader, File, PrintStream}
import java.nio.file.Files

class FileToStreamTailer(file: File, stream: PrintStream, intervalMsec: Int)
    extends Thread("FileToStreamTailerThread") with AutoCloseable {

  setDaemon(true)

  // if true, we won't read the whole file, but only new lines
  @volatile private var ignoreHead = true
  @volatile private var keepReading = true
  @volatile private var doFlush = false

  override def run(): Unit = {
    if (isInterrupted) {
      keepReading = false
    }
    var reader: BufferedReader = null
    try {
      while (keepReading || doFlush) {
        doFlush = false
        try {
          // Init reader, if not already done
          if (reader == null) {
            try {
              reader = Files.newBufferedReader(file.toPath)
            } catch {
              case _: java.io.IOException =>
                // nothing to ignore if file is initially missing
                ignoreHead = false
            }
          }
          if (reader != null) {
            // read lines
            try {
              var line = reader.readLine()
              while (line != null) {
                if (!ignoreHead) {
                  stream.println(line)
                }
                line = reader.readLine()
              }
              // we ignored once
              this.ignoreHead = false
            } catch {
              case _: java.io.IOException =>
              // could not read line or file vanished
            }
          }
        } finally {
          if (keepReading) {
            try Thread.sleep(intervalMsec)
            catch { case _: InterruptedException => }
          }
        }
      }
    } finally {
      if (reader != null) {
        try reader.close()
        catch { case _: java.io.IOException => }
      }
    }
  }

  override def interrupt(): Unit = {
    this.keepReading = false
    super.interrupt()
  }

  /** Force a next read, even if we interrupt the thread. */
  def flush(): Unit = {
    this.doFlush = true
  }

  override def close(): Unit = {
    flush()
    interrupt()
  }
}
