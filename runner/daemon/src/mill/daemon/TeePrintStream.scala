package mill.daemon

import java.io.PrintStream

class TeePrintStream(out1: PrintStream, out2: PrintStream) extends PrintStream(out1) {
  override def write(b: Int): Unit = synchronized {
    out1.write(b)
    out2.write(b)
  }

  override def write(b: Array[Byte]): Unit = synchronized {
    out1.write(b)
    out2.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
    out1.write(b, off, len)
    out2.write(b, off, len)
  }

  override def flush(): Unit = {
    out1.flush()
    out2.flush()
  }

  override def close(): Unit = {
    try {
      out1.close()
    } finally {
      out2.close()
    }
  }

  override def checkError(): Boolean = out1.checkError() || out2.checkError()

}
