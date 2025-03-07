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
    reportPrefix: () => Unit
) extends FilterOutputStream(out) {
  def this(linePrefix: String, out: OutputStream) = this(linePrefix, out, () => ())
  private val linePrefixBytes = linePrefix.getBytes("UTF-8")
  private val linePrefixNonEmpty = linePrefixBytes.length != 0
  private var isNewLine = true
  val buffer = new ByteArrayOutputStream()

  // Track both start and end colors for each line to properly handle multiline ANSI sequences
  private var currentLineStartColor: Long = 0
  private var currentLineEndColor: Long = 0
  private var escapeSequenceBuffer = new StringBuilder()
  private var inEscapeSequence = false

  override def write(b: Array[Byte]): Unit = write(b, 0, b.length)

  private def writeLinePrefixIfNecessary(): Unit = {
    if (isNewLine && linePrefixNonEmpty) {
      isNewLine = false
      buffer.write(linePrefixBytes)
      // Apply the color state from the start of the current line
      if (linePrefixNonEmpty && currentLineStartColor != 0)
        buffer.write(fansi.Attrs.emitAnsiCodes(0, currentLineStartColor).getBytes())
    }
  }

  def writeOutBuffer(): Unit = {
    if (buffer.size() > 0) reportPrefix()

    if (linePrefixNonEmpty) {
      val bufferString = buffer.toString
      if (bufferString.length > 0) {
        val s = fansi.Str.apply(bufferString, errorMode = fansi.ErrorMode.Sanitize)
        // Update both start and end colors for the current line
        currentLineEndColor = s.getColor(s.length - 1)
        if (isNewLine) {
          currentLineStartColor = currentLineEndColor
        }
      }
    }
    out.synchronized { buffer.writeTo(out) }
    buffer.reset()
  }

  private def processEscapeSequence(b: Int): Unit = {
    if (inEscapeSequence) {
      escapeSequenceBuffer.append(b.toChar)
      // Check if this is the end of the escape sequence
      if ((b >= 0x40 && b <= 0x7E) && b != '[') {
        inEscapeSequence = false
        val sequence = escapeSequenceBuffer.toString
        // Process the complete escape sequence
        if (sequence.contains("m")) { // Color code
          val s = fansi.Str.apply("\u001b" + sequence, errorMode = fansi.ErrorMode.Sanitize)
          if (s.length > 0) {
            currentLineEndColor = s.getColor(s.length - 1)
            if (isNewLine) {
              currentLineStartColor = currentLineEndColor
            }
          }
        }
        escapeSequenceBuffer.clear()
      }
    } else if (b == 0x1b) { // ESC
      inEscapeSequence = true
      escapeSequenceBuffer.clear()
      escapeSequenceBuffer.append(b.toChar)
    }
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
    var start = off
    var i = off
    val max = off + len
    while (i < max) {
      writeLinePrefixIfNecessary()
      processEscapeSequence(b(i))
      
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
    processEscapeSequence(b)
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
