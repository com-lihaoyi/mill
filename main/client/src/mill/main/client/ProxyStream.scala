package mill.main.client

import java.io.{IOException, InputStream, OutputStream}
import scala.util.control._

object ProxyStream{
  final val OUT: Int = 1
  final val ERR: Int = -1
  final val END: Int = 0


  def sendEnd(out: OutputStream): Unit = {
    // Synchronize on the OutputStream instance to ensure thread safety
    out.synchronized {
      out.write(ProxyStream.END)
      out.flush()
    }
  }


  class Output(destination: OutputStream, key: Int) extends OutputStream {

    override def write(b: Int): Unit = {
      destination.synchronized { // Synchronize on the destination OutputStream
        destination.write(key)
        destination.write(b)
      }
    }

    override def write(b: Array[Byte]): Unit = {
      if (b.nonEmpty) {
        destination.synchronized { // Synchronize on the destination OutputStream
          write(b, 0, b.length)
        }
      }
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      destination.synchronized { // Synchronize on the destination OutputStream
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

    override def flush(): Unit = {
      destination.synchronized { // Synchronize on the destination OutputStream
        destination.flush()
      }
    }

    override def close(): Unit = {
      destination.synchronized { // Synchronize on the destination OutputStream
        destination.close()
      }
    }
  }

  class Pumper(
      src: InputStream,
      destOut: OutputStream,
      destErr: OutputStream,
      synchronizer: AnyRef = new Object()
  ) extends Runnable {

    def preRead(src: InputStream): Unit = ()

    def preWrite(buffer: Array[Byte], length: Int): Unit = ()

    override def run(): Unit = {
      val buffer = Array.ofDim[Byte](1024)
      var shouldContinue = true

       val outer = new Breaks;

      outer.breakable {
			while (shouldContinue) {
				try {
					preRead(src);
					val header = src.read();
					// -1 means socket was closed, 0 means a ProxyStream.END was sent. Note
					// that only header values > 0 represent actual data to read:
					// - sign((byte)header) represents which stream the data should be sent to
					// - abs((byte)header) represents the length of the data to read and send
					if (header == -1 || header == 0)
						outer.break;
					else {
						val stream = if(header.toByte > 0) 1 else -1;
						val quantity0 = header.toByte;
						val quantity = Math.abs(quantity0);
						var offset = 0;
						var delta = -1;
						while (offset < quantity) {
							preRead(src);
							delta = src.read(buffer, offset, quantity - offset);
							if (delta == -1) {
                shouldContinue = false
								outer.break;
							} else {
								offset += delta;
							}
						}

            // println("buffer: " + buffer.mkString(","))
            // println(delta)
            // println(offset)

						if (delta != -1) {
							synchronizer.synchronized {
								preWrite(buffer, offset);
                // println("stream: " + stream)
								stream match {
                  case ProxyStream.OUT =>
                    // println("out")
                    destOut.write(buffer, 0, offset);
                    // outer.break;
                  case ProxyStream.ERR =>
                    // println("err")
                    destErr.write(buffer, 0, offset);
                    // outer.break;
								}
              }
							}
						}
					}
				 catch {
          case e:IOException =>
            shouldContinue = false
					  outer.break;
				}
			}
    }

    try {
      synchronizer.synchronized {
        destOut.flush()
        destErr.flush()
      }
    } catch {
      case _: IOException => ()
    }
    }

    def flush(): Unit = {
      synchronizer.synchronized {
        destOut.flush()
        destErr.flush()
      }
    }
  }


}
