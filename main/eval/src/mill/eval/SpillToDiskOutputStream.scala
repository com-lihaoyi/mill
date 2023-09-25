package mill.eval

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}

private[eval] class SpillToDiskOutputStream(spillSize: Int, spillPath: os.Path) extends OutputStream
    with geny.Readable {
  private var count = 0L
  private val boas = new ByteArrayOutputStream()
  private var out: OutputStream = boas

  def checkIncrement(incr: Int) = {
    if (count < spillSize && count + incr >= spillSize) {
      out = os.write.outputStream(spillPath)
      boas.writeTo(out)
      count += incr
    }
  }

  override def write(b: Int): Unit = {
    checkIncrement(1)
    boas.write(b)
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
    if (count < spillSize) f(new ByteArrayInputStream(boas.toByteArray))
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
