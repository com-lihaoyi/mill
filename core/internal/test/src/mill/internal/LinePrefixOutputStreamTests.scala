package mill.internal

import utest.*

import java.io.ByteArrayOutputStream

object LinePrefixOutputStreamTests extends TestSuite {
  val tests = Tests {
    test("charByChar") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream(s => baos.write(("PREFIX" + s).getBytes))
      for (b <- "hello\nworld\n!".getBytes()) lpos.write(b)
      lpos.flush()
      assert(baos.toString == "PREFIXhello\nPREFIXworld\nPREFIX!")
    }

    test("charByCharTrailingNewline") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream(s => baos.write(("PREFIX" + s).getBytes))
      for (b <- "hello\nworld\n".getBytes()) lpos.write(b)
      lpos.flush()
      assert(baos.toString == "PREFIXhello\nPREFIXworld\n")
    }

    test("allAtOnce") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream(s => baos.write(("PREFIX" + s).getBytes))
      val arr = "hello\nworld\n!".getBytes()
      lpos.write(arr)
      lpos.flush()

      assert(baos.toString == "PREFIXhello\nPREFIXworld\nPREFIX!")
    }

    test("allAtOnceTrailingNewline") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream(s => baos.write(("PREFIX" + s).getBytes))
      val arr = "hello\nworld\n".getBytes()
      lpos.write(arr)
      lpos.flush()

      assert(baos.toString == "PREFIXhello\nPREFIXworld\n")
    }

    test("allAtOnceDoubleNewline") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream(s => baos.write(("PREFIX" + s).getBytes))
      val arr = "hello\n\nworld\n\n".getBytes()
      lpos.write(arr)
      lpos.flush()

      val expected = "PREFIXhello\nPREFIX\nPREFIXworld\nPREFIX\n"
      assert(baos.toString == expected)
    }

    test("ranges") {
      for (str <- Seq("hello\nworld\n")) {
        val arr = str.getBytes()
        for (i1 <- Range(0, arr.length)) {
          for (i2 <- Range(i1, arr.length)) {
            for (i3 <- Range(i2, arr.length)) {
              val baos = new ByteArrayOutputStream()
              val lpos = new LinePrefixOutputStream(s => baos.write(("PREFIX" + s).getBytes))
              lpos.write(arr, 0, i1)
              lpos.write(arr, i1, i2 - i1)
              lpos.write(arr, i2, i3 - i2)
              lpos.write(arr, i3, arr.length - i3)
              lpos.flush()
              assert(baos.toString == "PREFIXhello\nPREFIXworld\n")
            }
          }
        }
      }

    }

    test("colors") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream(s => baos.write(("PREFIX" + s).getBytes))
      lpos.write(fansi.Color.Red("hello").render.getBytes)
      lpos.write('\n')
      lpos.flush()
      lpos.write(fansi.Color.Green("world").render.getBytes)
      lpos.write('\n')
      lpos.flush()

      val blueText = fansi.Color.Blue("one\ntwo\nthree\nfour\nfive").render.getBytes

      blueText.grouped(7).foreach { chunk =>
        lpos.write(chunk)
        lpos.flush()
      }

      val expected =
        "PREFIX" + fansi.Color.Red("hello").render + "\n" +
          "PREFIX" + fansi.Color.Green("world").render + "\n" +
          "PREFIX" + fansi.Color.Blue("one\n") +
          "PREFIX" + fansi.Color.Blue("two\n") +
          "PREFIX" + fansi.Color.Blue("three\n") +
          "PREFIX" + fansi.Color.Blue("four\n") +
          "PREFIX" + fansi.Color.Blue("five")

      assert(baos.toString == expected)
    }

  }
}
