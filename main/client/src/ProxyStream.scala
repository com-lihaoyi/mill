package mill.main.client

import java.io.{InputStream, IOException, OutputStream}
import scala.util.control.Breaks

object ProxyStream {
  val OUT = 1
  val ERR = -1
  val END = 0

  @throws[IOException]
  def sendEnd(out: OutputStream): Unit = {
    out.synchronized {
      out.write(END)
      out.flush()
    }
  }

  class Output(private val destination: OutputStream, private val key: Int) extends OutputStream {
    @throws[IOException]
    override def write(b: Int): Unit = {
      destination.synchronized {
        destination.write(key)
        destination.write(b)
      }
    }

    @throws[IOException]
    override def write(b: Array[Byte]): Unit = {
      if (b.nonEmpty) {
        destination.synchronized {
          write(b, 0, b.length)
        }
      }
    }

    @throws[IOException]
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      destination.synchronized {
        var i = 0
        while (i < len && i + off < b.length) {
          val chunkLength = Math.min(len - i, 127)
          if (chunkLength > 0) {
            destination.write(chunkLength * key)
            destination.write(b, off + i, Math.min(b.length - off - i, chunkLength))
            i += chunkLength
          }
        }
      }
    }

    @throws[IOException]
    override def flush(): Unit = {
      destination.synchronized {
        destination.flush()
      }
    }

    @throws[IOException]
    override def close(): Unit = {
      destination.synchronized {
        destination.close()
      }
    }
  }

  class Pumper(
      private val src: InputStream,
      private val destOut: OutputStream,
      private val destErr: OutputStream,
      private val synchronizer: AnyRef = new Object
  ) extends Runnable {

    private val breaks = Breaks
    import breaks.{break, breakable}

    def preRead(src: InputStream): Unit = {}

    def preWrite(buffer: Array[Byte], length: Int): Unit = {}

    override def run(): Unit = {
      val buffer = Array.ofDim[Byte](1024)
      breakable {
        while (true) {
          try {
            preRead(src)
            val header = src.read()
            if (header == -1 || header == END) break // Break on stream end
            else {
              val stream = if (header.toByte > 0) OUT else ERR
              val quantity = Math.abs(header.toByte)
              var offset = 0
              var delta = -1

              while (offset < quantity) {
                preRead(src)
                delta = src.read(buffer, offset, quantity - offset)
                if (delta == -1) break
                else offset += delta
              }

              if (delta != -1) {
                synchronizer.synchronized {
                  preWrite(buffer, offset)
                  stream match {
                    case OUT => destOut.write(buffer, 0, offset)
                    case ERR => destErr.write(buffer, 0, offset)
                  }
                }
              }
            }
          } catch {
            case _: IOException => break // Exit on IOException
          }
        }
      }

      try {
        synchronizer.synchronized {
          destOut.flush()
          destErr.flush()
        }
      } catch {
        case _: IOException => // Ignore flush exceptions
      }
    }

    @throws[IOException]
    def flush(): Unit = {
      synchronizer.synchronized {
        destOut.flush()
        destErr.flush()
      }
    }
  }
}
