package mill.main.client

import utest._
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, OutputStream}
import java.nio.file.{Files, Paths}
import scala.util.Random
import mill.main.client.ProxyStream
import mill.main.client.Util
import java.io.File

object ClientTests extends TestSuite {

  val tests = Tests {

    test("readWriteInt") {
      val examples = Array(
        0,
        1,
        126,
        127,
        128,
        254,
        255,
        256,
        1024,
        99999,
        1234567,
        Int.MaxValue,
        Int.MaxValue / 2,
        Int.MinValue
      )
      for (example0 <- examples; example <- Seq(-example0, example0)) {
        val o = new ByteArrayOutputStream()
        Util.writeInt(o, example)
        val i = new ByteArrayInputStream(o.toByteArray)
        val s = Util.readInt(i)
        assert(example == s)
        assert(i.available() == 0)
      }
    }

    test("readWriteString") {
      val examples = Array(
        "",
        "hello",
        "i am cow",
        "i am cow\nhear me moo\ni weigh twice as much as you",
        "我是一个叉烧包"
      )
      for (example <- examples) {
        checkStringRoundTrip(example)
      }
    }

    test("readWriteBigString") {
      val lengths = Array(0, 1, 126, 127, 128, 254, 255, 256, 1024, 99999, 1234567)
      for (length <- lengths) {
        val bigChars = Array.fill(length)('X')
        checkStringRoundTrip(new String(bigChars))
      }
    }

    test("tinyProxyInputOutputStream") {
      proxyInputOutputStreams(
        readSamples("/bandung.jpg").take(30),
        Array.emptyByteArray,
        10
      )
    }

    test("leftProxyInputOutputStream") {
      proxyInputOutputStreams(
        readSamples("/bandung.jpg", "/akanon.mid", "/gettysburg.txt", "/pip.tar.gz"),
        Array.emptyByteArray,
        2950
      )
    }

    test("rightProxyInputOutputStream") {
      proxyInputOutputStreams(
        Array.emptyByteArray,
        readSamples("/bandung.jpg", "/akanon.mid", "/gettysburg.txt", "/pip.tar.gz"),
        3000
      )
    }

    test("mixedProxyInputOutputStream") {
      proxyInputOutputStreams(
        readSamples("/bandung.jpg", "/gettysburg.txt"),
        readSamples("/akanon.mid", "/pip.tar.gz"),
        3050
      )
    }
  }

  def checkStringRoundTrip(example: String): Unit = {
    val o = new ByteArrayOutputStream()
    Util.writeString(o, example)
    val i = new ByteArrayInputStream(o.toByteArray)
    val s = Util.readString(i)
    assert(example == s)
    assert(i.available() == 0)
  }

  def readSamples(samples: String*): Array[Byte] = {

    val out = new ByteArrayOutputStream()
    for (sample <- samples) {
      val content = this.getClass.getResourceAsStream(sample).readAllBytes()
      out.write(content)
    }
    out.toByteArray
  }

  def proxyInputOutputStreams(samples1: Array[Byte], samples2: Array[Byte], chunkMax: Int): Unit = {
    val pipe = new ByteArrayOutputStream()
    val src1 = new ProxyStream.Output(pipe, ProxyStream.OUT)
    val src2 = new ProxyStream.Output(pipe, ProxyStream.ERR)

    val random = new Random(31337)
    var i1 = 0
    var i2 = 0

    while (i1 < samples1.length || i2 < samples2.length) {
      val chunk = random.nextInt(chunkMax)
      if (random.nextBoolean() && i1 < samples1.length) {
        src1.write(samples1, i1, Math.min(samples1.length - i1, chunk))
        src1.flush()
        i1 += chunk
      } else if (i2 < samples2.length) {
        src2.write(samples2, i2, Math.min(samples2.length - i2, chunk))
        src2.flush()
        i2 += chunk
      }
    }

    val bytes = pipe.toByteArray
    val dest1 = new ByteArrayOutputStream()
    val dest2 = new ByteArrayOutputStream()
    val pumper = new ProxyStream.Pumper(
      new ByteArrayInputStream(bytes),
      dest1,
      dest2
    )
    pumper.run()
    assert(dest1.toByteArray.sameElements(samples1))
    assert(dest2.toByteArray.sameElements(samples2))
  }
}
