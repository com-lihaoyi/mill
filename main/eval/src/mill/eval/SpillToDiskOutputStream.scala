package mill.eval

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}

/**
 * Similar in use as [[ByteArrayOutputStream]], but provides a configurable threshold
 * at which it will spill the data to a specified path on disk. Allows the benefits
 * of fast in-memory handling of small data while providing a slower but more scalable
 * handling for large data
 */
private[eval] class SpillToDiskOutputStream(spillSize: Int,
                                            spillPath: os.Path) extends OutputStream
    with geny.Readable {
  private var count = 0L
  var boas = new SpillToDiskOutputStream.ByteArrayOutputStream()
  private var out: OutputStream = boas
  var spilled0 = false
  def spilled = spilled0

  def checkIncrement(incr: Int) = {
    if (count < spillSize && count + incr >= spillSize) {
      out = os.write.outputStream(spillPath)
      boas.writeTo(out)
      spilled0 = true
    }
    count += incr
  }

  override def write(b: Int): Unit = {
    checkIncrement(1)
    out.write(b)
  }

  override def write(b: Array[Byte]): Unit = {
    checkIncrement(b.size)
    out.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    checkIncrement(len)
    out.write(b, off, len)
  }

  def result = if (count >= spillSize) Left(spillPath) else Right(boas.toByteArray)

  def size = count

  override def writeBytesTo(out: OutputStream): Unit = {
    if (count < spillSize) boas.writeTo(out)
    else os.Internals.transfer(os.read.inputStream(spillPath), out)
  }

  override def readBytesThrough[T](f: InputStream => T): T = {
    if (count < spillSize) f(boas.getInputStream)
    else {
      val is = os.read.inputStream(spillPath)
      try f(is)
      finally is.close()
    }
  }
  override def close() = {
    super.close()
    if (os.exists(spillPath)) os.remove(spillPath)

  }
}

private[eval] object SpillToDiskOutputStream{
  /**
   * Subclass to allow direct zero-copy conversion to [[ByteArrayInputStream]]
   */
  private[eval] class ByteArrayOutputStream extends java.io.ByteArrayOutputStream{
    def getInputStream = new ByteArrayInputStream(buf, 0, count)
  }
}