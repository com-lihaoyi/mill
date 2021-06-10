package mill.api

import java.io.{ByteArrayInputStream, InputStream, OutputStream}

/**
 * A dummy input stream containing an empty byte array.
 */
object DummyInputStream extends ByteArrayInputStream(Array())

/**
 * A dummy output stream that does nothing with what it consumes (think of it as `/dev/null`).
 */
object DummyOutputStream extends OutputStream {
  override def write(b: Int): Unit = ()
  override def write(b: Array[Byte]): Unit = ()
  override def write(b: Array[Byte], off: Int, len: Int): Unit = ()
}

trait StreamSupport {

  /**
   * Pump the data from the `src` stream into the `dest` stream.
   */
  def stream(src: InputStream, dest: OutputStream): Unit = {
    val buffer = new Array[Byte](4096)
    while ({
      src.read(buffer) match {
        case -1 => false
        case n =>
          dest.write(buffer, 0, n)
          true
      }
    }) ()
  }

}

private[api] object StreamSupport extends StreamSupport
