package mill.util


import java.io.{ByteArrayOutputStream, OutputStream}

/**
 * Prefixes the first and each new line with a dynamically provided prefix,
 * and buffers up each line in memory before writing to the [[out]] stream
 * to prevent individual lines from being mixed together
 *
 * @param linePrefix The function to provide the prefix.
 * @param out The underlying output stream.
 */
class LinePrefixOutputStream(
    linePrefix: String,
    out: OutputStream,
    reportPrefix: () => Unit
) extends OutputStream {

  private[this] val linePrefixBytes = linePrefix.getBytes("UTF-8")
  private[this] val linePrefixNonEmpty = linePrefixBytes.length != 0
  private[this] var isNewLine = true
  val buffer = new ByteArrayOutputStream()
  override def write(b: Array[Byte]): Unit = write(b, 0, b.length)
  private[this] def writeLinePrefixIfNecessary(): Unit = {
    if (isNewLine && linePrefixNonEmpty) {
      isNewLine = false
      buffer.write(linePrefixBytes)
    }
  }

  def writeOutBuffer() = {
    if (buffer.size() > 0) reportPrefix()
    out.synchronized { buffer.writeTo(out) }
    buffer.reset()
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
    var start = off
    var i = off
    val max = off + len
    while (i < max) {
      writeLinePrefixIfNecessary()
      if (b(i) == '\n') {
        i += 1 // +1 to include the newline
        buffer.write(b, start, i - start)
        isNewLine = true
        start = i
        writeOutBuffer()
      }
      i += 1
    }

    if (math.min(i, max) - start > 0){
      writeLinePrefixIfNecessary()
      buffer.write(b, start, math.min(i, max) - start)
      if (b(max - 1) == '\n') writeOutBuffer()
    }

  }

  override def write(b: Int): Unit = synchronized {
    writeLinePrefixIfNecessary()
    buffer.write(b)
    if (b == '\n') {
      writeOutBuffer()
      isNewLine = true
    }
  }

  override def flush(): Unit = synchronized {
    writeOutBuffer()
    out.flush()
  }

  override def close(): Unit = {
    flush()
    out.close()
  }
}
