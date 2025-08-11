package mill.api.daemon

import java.io.{ByteArrayInputStream, OutputStream}

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
