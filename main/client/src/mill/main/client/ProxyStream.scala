// package mill.main.client

// import java.io.{IOException, InputStream, OutputStream}

// object ProxyStream {
//   val OUT: Int = 1
//   val ERR: Int = -1
//   val END: Int = 0

//   def sendEnd(out: OutputStream): Unit = synchronized {
//     out.write(END)
//     out.flush()
//   }

//   class Output(destination: OutputStream, key: Int) extends OutputStream {
//     override def write(b: Int): Unit = synchronized {
//       destination.write(key)
//       destination.write(b)
//     }

//     override def write(b: Array[Byte]): Unit = {
//       if (b.nonEmpty) synchronized {
//         write(b, 0, b.length)
//       }
//     }

//     override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
//       var i = 0
//       while (i < len && i + off < b.length) {
//         val chunkLength = math.min(len - i, 127)
//         if (chunkLength > 0) {
//           destination.write(chunkLength * key)
//           destination.write(b, off + i, math.min(b.length - off - i, chunkLength))
//           i += chunkLength
//         }
//       }
//     }

//     override def flush(): Unit = synchronized {
//       destination.flush()
//     }

//     override def close(): Unit = synchronized {
//       destination.close()
//     }
//   }

//   class Pumper(
//       src: InputStream,
//       destOut: OutputStream,
//       destErr: OutputStream,
//       synchronizer: AnyRef = new Object()
//   ) extends Runnable {

//     def preRead(src: InputStream): Unit = {}

//     def preWrite(buffer: Array[Byte], length: Int): Unit = {}

//     override def run(): Unit = {
//       val buffer = new Arraye (true) {
//         try {
//           preRead(src)
//           val header = src.read()
//           // -1 means socket was closed, 0 means ProxyStream.END was sent
//           // Only header values > 0 represent actual data to read
//           // sign(header) represents which stream the data should go to
//           // abs(header) represents the length of the data to read
//           if (header == -1 || header == 0) return
//           else {
//             val stream = if (header > 0) ProxyStream.OUT else ProxyStream.ERR
//             val quantity0 = header.toByte
//             val quantity = Math.abs(quantity0)
//             var offset = 0
//             var delta = -1
//             while (offset < quantity) {
//               preRead(src)
//               delta = src.read(buffer, offset, quantity - offset)
//               if (delta == -1) {
//                 return
//               } else {
//                 offset += delta
//               }
//             }

//             if (delta != -1) synchronized(synchronizer) {
//               preWrite(buffer, offset)
//               stream match {
//                 case ProxyStream.OUT => destOut.write(buffer, 0, offset)
//                 case ProxyStream.ERR => destErr.write(buffer, 0, offset)
//               }
//             }
//           }
//         } catch {
//           case _: IOException => // happens when the upstream pipe is closed
//             return
//         }
//       }

//       try {
//         synchronized(synchronizer) {
//           destOut.flush()
//           destErr.flush()
//         }
//       } catch {
//         case _: IOException => // ignoring exception on flush
//       }
//     }

//     def flush(): Unit = synchronized(synchronizer) {
//       destOut.flush()
//       destErr.flush()
//     }
//   }
// }
