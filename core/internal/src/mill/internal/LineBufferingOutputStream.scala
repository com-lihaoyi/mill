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
  // Track if the last byte written was \r, so we can handle \r\n as a single line terminator
  private var lastByteWasCR = false

  override def write(b: Array[Byte]): Unit = write(b, 0, b.length)

  def writeOutBuffer(): Unit = {
    if (buffer.size() > 0) {
      onLineComplete(buffer)
      buffer.reset()
    }
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
    // If the previous single-byte write was \r and this batch doesn't start with \n,
    // we need to flush the pending \r as a standalone line terminator
    if (lastByteWasCR && len > 0 && b(off) != '\n') writeOutBuffer()
    lastByteWasCR = false

    var start = off
    var i = off
    val max = off + len
    while (i < max) {
      val c = b(i)
      if (c == '\n') {
        i += 1 // +1 to include the newline
        buffer.write(b, start, i - start)
        start = i
        writeOutBuffer()
      } else if (c == '\r') {
        // \r\n case: skip the \r and let the \n trigger the flush
        if (i + 1 < max && b(i + 1) == '\n') i += 1
        // Handle standalone \r (not followed by \n) as a line terminator
        // to support progress output that uses \r to overwrite the current line
        else {
          i += 1 // +1 to include the \r
          buffer.write(b, start, i - start)
          start = i
          writeOutBuffer()
        }
      } else {
        i += 1
      }
    }

    if (math.min(i, max) - start > 0) {
      buffer.write(b, start, math.min(i, max) - start)
      // Don't flush on trailing \r in batch mode - it might be followed by \n in next write
      if (b(max - 1) == '\n') writeOutBuffer()
    }
  }

  override def write(b: Int): Unit = synchronized {
    // If the previous byte was \r and this byte is not \n, flush the pending \r
    if (lastByteWasCR && b != '\n') writeOutBuffer()
    lastByteWasCR = false

    buffer.write(b)
    if (b == '\n') writeOutBuffer()
    // Don't flush yet - wait to see if the next byte is \n
    else if (b == '\r') lastByteWasCR = true
  }

  override def flush(): Unit = synchronized {
    // We ignore flushes, because we already flush on completed lines, and flushing
    // incomplete lines can result in all sorts of confusing logs being printed
    // writeOutBuffer()
  }

  override def close(): Unit = {
    writeOutBuffer()
  }
}
