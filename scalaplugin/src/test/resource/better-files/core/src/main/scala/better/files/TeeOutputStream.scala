package better.files

import java.io.OutputStream

/**
  * Write to multiple outputstreams at once
  * If error happens on any one while doing an operation, only the last error is reported
  * @param outs
  */
class TeeOutputStream(outs: OutputStream*) extends OutputStream {
  override def write(b: Int) = tryAll(outs)(_.write(b))
  override def flush() = tryAll(outs)(_.flush())
  override def write(b: Array[Byte]) = tryAll(outs)(_.write(b))
  override def write(b: Array[Byte], off: Int, len: Int) = tryAll(outs)(_.write(b, off, len))
  override def close() = tryAll(outs)(_.close())
}

/**
  * A sink outputstream similar to /dev/null - just consumes everything
  */
object NullOutputStream extends OutputStream {
  override def write(b: Int) = {}
}
