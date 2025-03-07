package mill.internal

import utest.*

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

    test("multilineAnsiColors") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream("PREFIX", baos)

      // Test with color spanning multiple lines.
      val redStart = "\u001b[31m" // Red color
      val reset = "\u001b[0m" // Reset color
      val input = s"${redStart}hello\nworld\n!${reset}"

      lpos.write(input.getBytes())
      lpos.flush()

      val expected = s"PREFIX${redStart}hello\nPREFIX${redStart}world\nPREFIX${redStart}!${reset}"
      assert(baos.toString == expected)
    }

    test("splitAnsiSequence") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream("PREFIX", baos)

      // Write the ANSI sequence in parts.
      lpos.write("\u001b".getBytes())
      lpos.write("[".getBytes())
      lpos.write("31".getBytes())
      lpos.write("m".getBytes())
      lpos.write("hello\n".getBytes())
      lpos.write("world".getBytes())
      lpos.write("\u001b[0m".getBytes())
      lpos.flush()

      val expected = s"PREFIX\u001b[31mhello\nPREFIX\u001b[31mworld\u001b[0m"
      assert(baos.toString == expected)
    }

    test("multipleColors") {
      val baos = new ByteArrayOutputStream()
      val lpos = new LinePrefixOutputStream("PREFIX", baos)

      val red = "\u001b[31m" // Red
      val green = "\u001b[32m" // Green
      val blue = "\u001b[34m" // Blue
      val reset = "\u001b[0m" // Reset

      val input = s"${red}hello${green}\nworld${blue}\n!${reset}"
      lpos.write(input.getBytes())
      lpos.flush()

      val expected =
        s"PREFIX${red}hello${green}\nPREFIX${green}world${blue}\nPREFIX${blue}!${reset}"
      assert(baos.toString == expected)
    }
  }
}
