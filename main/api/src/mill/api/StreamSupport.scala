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

@deprecated("StreamSupport will be removed in Mill 1.0", "Mill 0.12.17")
trait StreamSupport {

  /**
   * Pump the data from the `src` stream into the `dest` stream.
   */
  @deprecated(
    "StreamSupport.stream will be removed in Mill 1.0. Use `os.Internals.transfer` instead.",
    "Mill 0.12.17"
  )
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

@deprecated("StreamSupport will be removed in Mill 1.0", "Mill 0.12.17")
private[api] object StreamSupport extends StreamSupport
