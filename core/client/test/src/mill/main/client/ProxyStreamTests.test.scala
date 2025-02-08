import java.io._

import utest._
import mill.main.client.ProxyStream
import scala.concurrent.Future

// class TeeOutputStream(out1: OutputStream, out2: OutputStream) extends OutputStream {
//   println("WARNING: On JVM TeeOutputStream comes from apache commons. It is not availble in Scala Native")
//   println("I've tried to hack together a naive implementation, but it may be wrong and risky ...")
//   override def write(b: Int): Unit = {
//     out1.write(b)
//     out2.write(b)
//   }

//   override def write(b: Array[Byte]): Unit = {
//     out1.write(b)
//     out2.write(b)
//   }

//   override def write(b: Array[Byte], off: Int, len: Int): Unit = {
//     out1.write(b, off, len)
//     out2.write(b, off, len)
//   }

//   override def flush(): Unit = {
//     out1.flush()
//     out2.flush()
//   }

//   override def close(): Unit = {
//     try out1.close()
//     finally out2.close()
//   }
// }

object ProxyStreamTests extends TestSuite {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  val tests = Tests {
    test("fuzzTests") {
      val interestingLengths = Array(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 40, 50, 100, 126, 127, 128, 129, 130, 253, 254, 255, 256, 257, 1000, 2000, 4000, 8000
      )
      val interestingBytes = Array[Byte](
        -1, -127, -126, -120, -100, -80, -60, -40, -20, -10, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 10, 20, 40, 60, 80, 100, 120, 125, 126, 127
      )

      for (n <- interestingLengths) {
        println(s"ProxyStreamTests fuzzing length $n")
        for (r <- 1 until interestingBytes.length + 1) {
          val outData = Array.ofDim[Byte](n)
          val errData = Array.ofDim[Byte](n)

          for (j <- 0 until n) {
            outData(j) = interestingBytes((j + r) % interestingBytes.length)
            errData(j) = (-interestingBytes((j + r) % interestingBytes.length)).toByte
          }

          test0(outData, errData, r, gracefulEnd = false)
          test0(outData, errData, r, gracefulEnd = true)
        }
      }
    }
  }

  def test0(outData: Array[Byte], errData: Array[Byte], repeats: Int, gracefulEnd: Boolean): Unit = {
    val pipedOutputStream = new PipedOutputStream()
    val pipedInputStream = new PipedInputStream()
    pipedInputStream.connect(pipedOutputStream)

    val srcOut = new ProxyStream.Output(pipedOutputStream, ProxyStream.OUT)
    val srcErr = new ProxyStream.Output(pipedOutputStream, ProxyStream.ERR)

    val destOut = new ByteArrayOutputStream()
    val destErr = new ByteArrayOutputStream()
    val destCombined = new ByteArrayOutputStream()

    val pumper = new ProxyStream.Pumper(
      pipedInputStream,
      new TeeOutputStream(destOut, destCombined),
      new TeeOutputStream(destErr, destCombined)
    )

    val writerThread = new Thread(() => {
      try {
        for (_ <- 0 until repeats) {
          srcOut.write(outData)
          srcErr.write(errData)
        }
        if (gracefulEnd) ProxyStream.sendEnd(pipedOutputStream)
        else pipedOutputStream.close()
      } catch {
        case e: Exception => e.printStackTrace()
      }
    })

    val pumperThread = new Thread(pumper)

    writerThread.start()
    pumperThread.start()

    writerThread.join()
    pumperThread.join()

    val repeatedOutData = repeatArray(outData, repeats)
    val repeatedErrData = repeatArray(errData, repeats)
    val combinedData = repeatArray(outData ++ errData, repeats)

    assert(destOut.toByteArray.sameElements(repeatedOutData))
    assert(destErr.toByteArray.sameElements(repeatedErrData))
    assert(destCombined.toByteArray.sameElements(combinedData))
  }

  def repeatArray(original: Array[Byte], n: Int): Array[Byte] = {
    val result = Array.ofDim[Byte](original.length * n)
    for (i <- 0 until n) {
      System.arraycopy(original, 0, result, i * original.length, original.length)
    }
    result
  }
}
