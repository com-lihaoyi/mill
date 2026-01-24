package mill.daemon

import java.nio.file.StandardOpenOption

/**
 * An OutputStream that writes to a console log file and automatically
 * truncates it every `maxNewlines` newlines to prevent unbounded growth.
 */
private[daemon] class RotatingConsoleLogOutputStream(
    consoleLogFile: os.Path,
    maxNewlines: Int = 1000
) extends java.io.OutputStream {
  private var newlineCount = 0
  private var underlying: java.io.OutputStream = openStream()

  private def openStream(): java.io.OutputStream = {
    os.write.outputStream(
      consoleLogFile,
      createFolders = true,
      openOptions = Seq(
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
    )
  }

  private def incrementNewlineAndMaybeRotate(): Unit = {
    newlineCount += 1
    if (newlineCount >= maxNewlines) {
      underlying.close()
      underlying = openStream()
      newlineCount = 0
    }
  }

  override def write(b: Int): Unit = synchronized {
    underlying.write(b)
    if (b == '\n') incrementNewlineAndMaybeRotate()
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
    var pos = off
    val end = off + len
    while (pos < end) {
      // Find the next newline
      var nextNewline = pos
      while (nextNewline < end && b(nextNewline) != '\n') nextNewline += 1

      if (nextNewline < end) {
        // Found a newline - write up to and including it
        underlying.write(b, pos, nextNewline - pos + 1)
        incrementNewlineAndMaybeRotate()
        pos = nextNewline + 1
      } else {
        underlying.write(b, pos, end - pos)
        pos = end
      }
    }
  }

  override def flush(): Unit = synchronized { underlying.flush() }
  override def close(): Unit = synchronized { underlying.close() }
}
