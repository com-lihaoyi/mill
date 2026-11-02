package mill.client

import java.io.{ByteArrayOutputStream, InputStream, PrintStream}
import java.nio.charset.Charset

class FileToStreamTailer(file: os.Path, stream: PrintStream, intervalMsec: Int)
    extends Thread("FileToStreamTailerThread") with AutoCloseable {

  setDaemon(true)

  @volatile private var keepReading = true

  // START guarded vars
  private val lock = new Object

  // if true, we won't read the whole file, only new lines
  private var ignoreHead = true
  private var fileStream: Option[InputStream] = None
  private val chunk = new Array[Byte](8192)
  private val buffer = new ByteArrayOutputStream()
  // END guarded vars

  override def run(): Unit = {
    while (keepReading) {
      copyNewLines()
      try Thread.sleep(intervalMsec)
      catch {
        case _: InterruptedException =>
      }
    }
    printBuffer()
    closeInput()
  }

  override def interrupt(): Unit = {
    this.keepReading = false
    super.interrupt()
  }

  /**
   * Force a next read on the calling thread, also copying any unterminated line.
   */
  def flush(): Unit = lock.synchronized {
    copyNewLines()
    printBuffer()
  }

  override def close(): Unit = {
    flush()
    closeInput()
    interrupt()
  }

  private def closeInput(): Unit = lock.synchronized {
    try fileStream.foreach(_.close())
    catch {
      case _: java.io.IOException => /* ignore */
    }
    fileStream = None
  }

  private def copyNewLines(): Unit = lock.synchronized {
    input.foreach(inputStream => {
      try {
        if (ignoreHead) {
          var n = inputStream.read(chunk)
          while (n > 0) n = inputStream.read(chunk)
          // we ignored once
          ignoreHead = false
        } else {
          var n = inputStream.read(chunk)
          while (n > 0) {
            var start = 0
            var i = 0
            while (i < n) {
              if (chunk(i) == '\n') {
                buffer.write(chunk, start, i - start + 1)
                printBuffer()
                start = i + 1
              }
              i += 1
            }
            buffer.write(chunk, start, n - start)
            n = inputStream.read(chunk)
          }
        }
      } catch {
        case _: java.io.IOException => /* could not read or file vanished */
          closeInput()
      }
    })
  }

  private def input: Option[InputStream] = lock.synchronized {
    fileStream.orElse {
      // Check existence first to avoid allocating a NoSuchFileException on every
      // poll while the file is missing.
      if (os.exists(file)) {
        try fileStream = Some(os.read.inputStream(file))
        catch {
          case _: java.io.IOException =>
            // file vanished between the exists check and open
            ignoreHead = false
        }
      } else {
        // nothing to ignore if file is initially missing
        ignoreHead = false
      }
      fileStream
    }
  }

  private def printBuffer(): Unit = {
    if (buffer.size() > 0) {
      val bytes = buffer.toByteArray
      buffer.reset()
      stream.print(new String(bytes, 0, bytes.length, Charset.defaultCharset()))
      stream.flush()
    }
  }
}
