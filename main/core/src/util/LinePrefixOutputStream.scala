package mill.util

import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Prefixes the first and each new line with a dynamically provided prefix.
 * @param linePrefix The function to provide the prefix.
 * @param out The underlying output stream.
 */
class LinePrefixOutputStream(
  val linePrefix: () => String,
  out: OutputStream
) extends FilterOutputStream(out) {

  private[this] var isFirst = true

  override def write(b: Array[Byte]) = write(b, 0, b.length)
  override def write(b: Array[Byte], off: Int, len: Int) = for (i <- off until len) {
    write(b(i))
  }
  override def write(b: Int) = {
    if (isFirst) {
      isFirst = false
      linePrefix() match {
        case null =>
        case prefix => out.write(prefix.getBytes("UTF-8"))
      }
    }
    out.write(b)
    if (b == '\n') isFirst = true
  }

}
