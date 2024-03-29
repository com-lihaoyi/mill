package mill.util

import java.io.{ByteArrayOutputStream, FilterOutputStream, OutputStream}

/**
 * Prefixes the first and each new line with a dynamically provided prefix.
 * @param linePrefix The function to provide the prefix.
 * @param out The underlying output stream.
 */
class LinePrefixOutputStream(
    linePrefix: String,
    out: OutputStream
) extends FilterOutputStream(out) {

  private[this] var isFirst = true

  val buffer = new ByteArrayOutputStream()
  override def write(b: Array[Byte]): Unit = write(b, 0, b.length)
  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    var i = off
    while (i < len) {
      write(b(i))
      i += 1
    }
  }
  override def write(b: Int): Unit = {

    if (isFirst) {
      isFirst = false
      if (linePrefix != "") {
        buffer.write(linePrefix.getBytes("UTF-8"))
      }
    }
    buffer.write(b)
    if (b == '\n') {
      flush()
      isFirst = true
    }
  }

  override def flush(): Unit = {
    out.synchronized {
      out.write(buffer.toByteArray)
    }
    buffer.reset()
    super.flush()
  }
}
