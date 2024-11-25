// package mill.main.client

// import java.io.{InputStream, IOException, OutputStream}

// /**
//  * Logic to capture a pair of streams (typically stdout and stderr), combining
//  * them into a single stream, and splitting it back into two streams later while
//  * preserving ordering. This is useful for capturing stderr and stdout and forwarding
//  * them to a terminal while strictly preserving the ordering, i.e., users won't see
//  * exception stack traces and printlns arriving jumbled up and impossible to debug.
//  *
//  * This works by converting writes from either of the two streams into packets of
//  * the form:
//  *
//  *  1 byte         n bytes
//  * | header |         body |
//  *
//  * Where header is a single byte of the form:
//  *
//  * - header > 0: this packet is for the `OUT` stream
//  * - header < 0: this packet is for the `ERR` stream
//  * - abs(header): the length of the packet body, in bytes
//  * - header == 0: end of the stream
//  *
//  * Writes to either of the two `Output`s are synchronized on the shared
//  * `destination` stream, ensuring that they always arrive complete and without
//  * interleaving. On the other side, a `Pumper` reads from the combined
//  * stream, forwards each packet to its respective destination stream, or terminates
//  * when it hits a packet with `header == 0`.
//  */
// object ProxyStream {
//   val OUT: Int = 1
//   val ERR: Int = -1
//   val END: Int = 0

//   @throws[IOException]
//   def sendEnd(out: OutputStream): Unit = out.synchronized {
//     out.write(END)
//     out.flush()
//   }

//   class Output(destination: OutputStream, key: Int) extends OutputStream {

//     @throws[IOException]
//     override def write(b: Int): Unit = destination.synchronized {
//       destination.write(key)
//       destination.write(b)
//     }

//     @throws[IOException]
//     override def write(b: Array[Byte]): Unit = {
//       if (b.nonEmpty) {
//         write(b, 0, b.length)
//       }
//     }

//     @throws[IOException]
//     override def write(b: Array[Byte], off: Int, len: Int): Unit = destination.synchronized {
//       var i = 0
//       while (i < len && i + off < b.length) {
//         val chunkLength = Math.min(len - i, 127)
//         if (chunkLength > 0) {
//           destination.write(chunkLength * key)
//           destination.write(b, off + i, Math.min(b.length - off - i, chunkLength))
//           i += chunkLength
//         }
//       }
//     }

//     @throws[IOException]
//     override def flush(): Unit = destination.synchronized {
//       destination.flush()
//     }

//     @throws[IOException]
//     override def close(): Unit = destination.synchronized {
//       destination.close()
//     }
//   }

//   class Pumper(
//       src: InputStream,
//       destOut: OutputStream,
//       destErr: OutputStream,
//       synchronizer: AnyRef = new Object
//   ) extends Runnable {

//     def preRead(src: InputStream): Unit = {}

//     def preWrite(buffer: Array[Byte], length: Int): Unit = {}

//     override def run(): Unit = {
//       try {
//         val buffer = new Array {
//           while (true) {
//             preRead(src)
//             val header = src.read()
//             if (header == -1 || header == 0) return

//             val stream = if (header > 0) OUT else ERR
//             val quantity = Math.abs(header)
//             var offset = 0
//             var delta = -1

//             while (offset < quantity) {
//               preRead(src)
//               delta = src.read(buffer, offset, quantity - offset)
//               if (delta == -1) return
//               else offset += delta
//             }

//             if (delta != -1) synchronizer.synchronized {
//               preWrite(buffer, offset)
//               stream match {
//                 case OUT => destOut.write(buffer, 0, offset)
//                 case ERR => destErr.write(buffer, 0, offset)
//               }
//             }
//           }
//         }
//       } catch {
//         case _: IOException => // This happens when the upstream pipe is closed
//       } finally {
//         try {
//           synchronizer.synchronized {
//             destOut.flush()
//             destErr.flush()
//           }
//         } catch {
//           case _: IOException => // Ignore flush failures during cleanup
//         }
//       }
//     }

//     @throws[IOException]
//     def flush(): Unit = synchronizer.synchronized {
//       destOut.flush()
//       destErr.flush()
//     }
//   }
// }
