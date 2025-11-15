package mill.internal

import java.io.{ByteArrayOutputStream, FilterOutputStream, OutputStream}

/**
 * Prefixes the first and each new line with a dynamically provided prefix,
 * and buffers up each line in memory before writing to the [[out]] stream
 * to prevent individual lines from being mixed together
 *
 * @param linePrefix The function to provide the prefix.
 * @param out The underlying output stream.
 */
private[mill] class LinePrefixOutputStream(
    linePrefix: String,
    out: OutputStream,
    reportPrefix: String => Unit
) extends FilterOutputStream(out) {
  def this(linePrefix: String, out: OutputStream) = this(linePrefix, out, _ => ())
  private val linePrefixBytes = linePrefix.getBytes("UTF-8")
  private val linePrefixNonEmpty = linePrefixBytes.length != 0
  private var isNewLine = true
  val buffer = new ByteArrayOutputStream()

  // Make sure we preserve the end-of-line ANSI colors every time we write out the buffer, and
  // re-apply them after every line prefix. This helps ensure the line prefix color/resets does
  // not muck up the rendering of color sequences that affect multiple lines in the terminal
  private var endOfLastLineColor: Long = 0
  override def write(b: Array[Byte]): Unit = write(b, 0, b.length)
  private def writeLinePrefixIfNecessary(): Unit = {
    if (isNewLine && linePrefixNonEmpty) {
      isNewLine = false
      buffer.write(fansi.Attrs.emitAnsiCodes(endOfLastLineColor, 0).getBytes())

      buffer.write(linePrefixBytes)
      if (linePrefixNonEmpty) {
        buffer.write(fansi.Attrs.emitAnsiCodes(0, endOfLastLineColor).getBytes())
      }
    }
  }

  def writeOutBuffer(): Unit = {
    if (linePrefixNonEmpty) {
      val bufferString = fansi.Attrs.emitAnsiCodes(0, endOfLastLineColor) + buffer.toString
      // Make sure we add a suffix "x" to the `bufferString` before computing the last
      // color. This ensures that any trailing colors in the original `bufferString` do not
      // get ignored since they would affect zero characters.
      val s = fansi.Str.apply(bufferString + "x", errorMode = fansi.ErrorMode.Sanitize)
      endOfLastLineColor = s.getColor(s.length - 1)
    }

    if (buffer.size() > 0) {
      val str0 = new String(buffer.toByteArray)
      val str = // Hack to get rid of the line prefix so the PromptLogger has a chance to skip it
        if (linePrefixNonEmpty) str0.dropWhile(_ != ' ').drop(1)
        else str0

      reportPrefix(str)
    }

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
      } else {
        i += 1
      }
    }

    if (math.min(i, max) - start > 0) {
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
