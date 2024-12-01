// package mill.main.client

// import java.io._
// import org.apache.commons.io.output.TeeOutputStream
// import org.junit.Assert.assertArrayEquals
// import org.junit.Test

// class ProxyStreamTests {

//   /**
//    * Ad-hoc fuzz tests to try and make sure the stuff we write into the
//    * `ProxyStreams.Output` and read out of the `ProxyStreams.Pumper` ends up
//    * being the same
//    */
//   @Test
//   def test(): Unit = {
//     val interestingLengths = Array(
//       1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 40, 50, 100, 126, 127, 128, 129, 130, 253, 254, 255,
//       256, 257, 1000, 2000, 4000, 8000
//     )
//     val interestingBytes = Array(
//       -1, -127, -126, -120, -100, -80, -60, -40, -20, -10, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 10,
//       20, 40, 60, 80, 100, 120, 125, 126, 127
//     )

//     for (n <- interestingLengths) {
//       println(s"ProxyStreamTests fuzzing length $n")
//       for (r <- 1 until interestingBytes.length + 1) {
//         val outData = new Array[Byte](n)
//         val errData = new Array[Byte](n)

//         for (j <- 0 until n) {
//           // fill test data blobs with arbitrary bytes from `interestingBytes`, negating
//           // the bytes we use for `errData` so we can distinguish it from `outData`
//           outData(j) = interestingBytes((j + r) % interestingBytes.length).toByte
//           errData(j) = (-interestingBytes((j + r) % interestingBytes.length)).toByte
//         }

//         // Run all tests both with the format `ProxyStream.END` packet
//         // being sent as well as when the stream is unceremoniously closed

//         // println(outData.mkString(", "))
//         // println(errData.mkString(", "))

//         test0(outData, errData, r, gracefulEnd = false)
//         test0(outData, errData, r, gracefulEnd = true)
//       }
//     }
//   }

//   def test0(
//       outData: Array[Byte],
//       errData: Array[Byte],
//       repeats: Int,
//       gracefulEnd: Boolean
//   ): Unit = {
//     val pipedOutputStream = new PipedOutputStream()
//     val pipedInputStream = new PipedInputStream(1000000)

//     pipedInputStream.connect(pipedOutputStream)

//     val srcOut = new ProxyStream.Output(pipedOutputStream, ProxyStream.OUT)
//     val srcErr = new ProxyStream.Output(pipedOutputStream, ProxyStream.ERR)

//     // Capture both the destOut/destErr from the pumper, as well as the destCombined
//     // to ensure the individual streams contain the right data and combined stream
//     // is in the right order
//     val destOut = new ByteArrayOutputStream()
//     val destErr = new ByteArrayOutputStream()
//     val destCombined = new ByteArrayOutputStream()

//     val pumper = new ProxyStream.Pumper(
//       pipedInputStream,
//       new TeeOutputStream(destOut, destCombined),
//       new TeeOutputStream(destErr, destCombined)
//     )

//     new Thread(new Runnable {
//       override def run(): Unit = {
//         try {
//           for (_ <- 0 until repeats) {
//             srcOut.write(outData)
//             srcErr.write(errData)
//           }

//           if (gracefulEnd) ProxyStream.sendEnd(pipedOutputStream)
//           else pipedOutputStream.close()
//         } catch {
//           case e: Exception => e.printStackTrace()
//         }
//       }
//     }).start()

//     val pumperThread = new Thread(pumper)

//     pumperThread.start()
//     pumperThread.join()

//     // println("destout" + destOut.toByteArray.mkString(", "))
//     // println("errout" + destErr.toByteArray.mkString(", "))

//     // Check that the individual `destOut` and `destErr` contain the correct bytes
//     assertArrayEquals(repeatArray(outData, repeats), destOut.toByteArray)
//     assertArrayEquals(repeatArray(errData, repeats), destErr.toByteArray)

//     // Check that the combined `destCombined` contains the correct bytes in the correct order
//     val combinedData = new Array[Byte](outData.length + errData.length)
//     System.arraycopy(outData, 0, combinedData, 0, outData.length)
//     System.arraycopy(errData, 0, combinedData, outData.length, errData.length)

//     assertArrayEquals(repeatArray(combinedData, repeats), destCombined.toByteArray)
//   }

//   private def repeatArray(original: Array[Byte], n: Int): Array[Byte] = {
//     val result = new Array[Byte](original.length * n)
//     for (i <- 0 until n) {
//       System.arraycopy(original, 0, result, i * original.length, original.length)
//     }
//     result
//   }
// }
