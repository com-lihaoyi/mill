// package mill.main.client

// import java.io.{IOException, InputStream, OutputStream}

// object ProxyStream {

//   val OUT: Int = 1
//   val ERR: Int = -1
//   val END: Int = 0

//   def sendEnd(out: OutputStream): Unit = {
//     // Synchronize on the OutputStream instance to ensure thread safety
//     out.synchronized {
//       out.write(END)
//       out.flush()
//     }
//   }

//   class Output(destination: OutputStream, key: Int) extends OutputStream {

//     override def write(b: Int): Unit = {
//       destination.synchronized { // Synchronize on the destination OutputStream
//         destination.write(key)
//         destination.write(b)
//       }
//     }

//     override def write(b: Array[Byte]): Unit = {
//       if (b.nonEmpty) {
//         destination.synchronized { // Synchronize on the destination OutputStream
//           write(b, 0, b.length)
//         }
//       }
//     }

//     override def write(b: Array[Byte], off: Int, len: Int): Unit = {
//       destination.synchronized { // Synchronize on the destination OutputStream
//         var i = 0
//         while (i < len && i + off < b.length) {
//           val chunkLength = Math.min(len - i, 127)
//           if (chunkLength > 0) {
//             destination.write(chunkLength * key)
//             destination.write(b, off + i, Math.min(b.length - off - i, chunkLength))
//             i += chunkLength
//           }
//         }
//       }
//     }

//     override def flush(): Unit = {
//       destination.synchronized { // Synchronize on the destination OutputStream
//         destination.flush()
//       }
//     }

//     override def close(): Unit = {
//       destination.synchronized { // Synchronize on the destination OutputStream
//         destination.close()
//       }
//     }
//   }

//   class Pumper(
//       src: InputStream,
//       destOut: OutputStream,
//       destErr: OutputStream,
//       synchronizer: AnyRef = new Object()
//   ) extends Runnable {

//     def preRead(src: InputStream): Unit = {}

//     def preWrite(buffer: scala.collection.mutable.ArrayBuffer[Byte], length: Int): Unit = {}

//     override def run(): Unit = {
//       val buffer = new scala.collection.mutable.ArrayBuffer[Byte](1024)
//       while (true) {
//         try {
//           preRead(src)
//           val header = src.read()

//           header match {
//             case -1 => ()
//             case 0 => ()
//             case _ =>
//               val stream = if (header > 0) OUT else ERR
//               val quantity = Math.abs(header.toByte)
//               var offset = 0
//               var delta = -1

//               while (offset < quantity) {
//                 preRead(src)
//                 delta = src.read(buffer.toArray, offset, quantity - offset)
//                 if (delta == -1) return
//                 else offset += delta
//               }

//               if (delta != -1) {
//                 synchronizer.synchronized {
//                   preWrite(buffer, offset)
//                   stream match {
//                     case ProxyStream.OUT =>
//                       destOut.write(buffer.toArray, 0, offset)
//                     case ProxyStream.ERR =>
//                       destErr.write(buffer.toArray, 0, offset)
//                   }
//                 }
//               }

//           }
//         } catch {
//           case _: IOException => ()
//         }
//       }

//       try {
//         synchronizer.synchronized {
//           destOut.flush()
//           destErr.flush()
//         }
//       } catch {
//         case _: IOException =>
//       }
//     }

//     def flush(): Unit = {
//       synchronizer.synchronized {
//         destOut.flush()
//         destErr.flush()
//       }
//     }
//   }
// }
