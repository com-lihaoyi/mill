package mill.util

import utest._

import java.io.ByteArrayOutputStream

object LinePrefixOutputStreamTests extends TestSuite {
  val tests = Tests {
    test("charByChar") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream("PREFIX", baos)
      for (b <- "hello\nworld\n!".getBytes()) lpos.write(b)
      lpos.flush()
      assert(baos.toString == "PREFIXhello\nPREFIXworld\nPREFIX!")
    }

    test("charByCharTrailingNewline") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream("PREFIX", baos)
      for (b <- "hello\nworld\n".getBytes()) lpos.write(b)
      lpos.flush()
      assert(baos.toString == "PREFIXhello\nPREFIXworld\n")
    }

    test("allAtOnce") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream("PREFIX", baos)
      val arr = "hello\nworld\n!".getBytes()
      lpos.write(arr)
      lpos.flush()

      assert(baos.toString == "PREFIXhello\nPREFIXworld\nPREFIX!")
    }

    test("allAtOnceTrailingNewline") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream("PREFIX", baos)
      val arr = "hello\nworld\n".getBytes()
      lpos.write(arr)
      lpos.flush()

      assert(baos.toString == "PREFIXhello\nPREFIXworld\n")
    }

    test("allAtOnceDoubleNewline") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream("PREFIX", baos)
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
              val lpos = new LinePrefixOutputStream("PREFIX", baos)
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
  }
}
