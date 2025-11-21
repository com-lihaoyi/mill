package mill.internal

import java.io.{ByteArrayOutputStream, OutputStream}

/**
 * Prefixes the first and each new line with a dynamically provided prefix,
 * and buffers up each line in memory before writing to the [[out]] stream
 * to prevent individual lines from being mixed together
 *
 * @param linePrefix The function to provide the prefix.
 * @param out The underlying output stream.
 */
class LineBufferingOutputStream(onLineComplete: ByteArrayOutputStream => Unit)
    extends OutputStream {

  val buffer = new ByteArrayOutputStream()

  override def write(b: Array[Byte]): Unit = write(b, 0, b.length)

  def writeOutBuffer(): Unit = {
    if (buffer.size() > 0) {
      onLineComplete(buffer)
      buffer.reset()
    }
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
    var start = off
    var i = off
    val max = off + len
    while (i < max) {
      if (b(i) == '\n') {
        i += 1 // +1 to include the newline
        buffer.write(b, start, i - start)
        start = i
        writeOutBuffer()
      } else {
        i += 1
      }
    }

    if (math.min(i, max) - start > 0) {
      buffer.write(b, start, math.min(i, max) - start)
      if (b(max - 1) == '\n') writeOutBuffer()
    }

  }

  override def write(b: Int): Unit = synchronized {
    buffer.write(b)
    if (b == '\n') writeOutBuffer()
  }

  override def flush(): Unit = synchronized {
    writeOutBuffer()
  }

  override def close(): Unit = {
    writeOutBuffer()
  }
}
