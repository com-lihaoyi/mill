package mill.util

import utest._

object MultilinePromptLoggerTests extends TestSuite {

  val tests = Tests {
    test("lastIndexOfNewline") {
      // Fuzz test to make sure our custom fast `lastIndexOfNewline` logic behaves
      // the same as a slower generic implementation using `.slice.lastIndexOf`
      val allSampleByteArrays = Seq[Array[Byte]](
        Array(1),
        Array('\n'),
        Array(1, 1),
        Array(1, '\n'),
        Array('\n', 1),
        Array('\n', '\n'),
        Array(1, 1, 1),
        Array(1, 1, '\n'),
        Array(1, '\n', 1),
        Array('\n', 1, 1),
        Array(1, '\n', '\n'),
        Array('\n', 1, '\n'),
        Array('\n', '\n', 1),
        Array('\n', '\n', '\n'),
        Array(1, 1, 1, 1),
        Array(1, 1, 1, '\n'),
        Array(1, 1, '\n', 1),
        Array(1, '\n', 1, 1),
        Array('\n', 1, 1, 1),
        Array(1, 1, '\n', '\n'),
        Array(1, '\n', '\n', 1),
        Array('\n', '\n', 1, 1),
        Array(1, '\n', 1, '\n'),
        Array('\n', 1, '\n', 1),
        Array('\n', 1, 1, '\n'),
        Array('\n', '\n', '\n', 1),
        Array('\n', '\n', 1, '\n'),
        Array('\n', 1, '\n', '\n'),
        Array(1, '\n', '\n', '\n'),
        Array('\n', '\n', '\n', '\n')
      )

      for (sample <- allSampleByteArrays) {
        for (start <- Range(0, sample.length)) {
          for (len <- Range(0, sample.length - start)) {
            val found = MultilinePromptLogger.lastIndexOfNewline(sample, start, len)
            val expected0 = sample.slice(start, start + len).lastIndexOf('\n')
            val expected = expected0 + start
            def assertMsg =
              s"found:$found, expected$expected, sample:${sample.toSeq}, start:$start, len:$len"
            if (expected0 == -1) Predef.assert(found == -1, assertMsg)
            else Predef.assert(found == expected, assertMsg)
          }
        }
      }
    }
    test("renderHeader"){
      import MultilinePromptLogger.renderHeader

      def check(prefix: String, title: String, suffix: String, maxWidth: Int, expected: String) = {
        val rendered = renderHeader(prefix, title, suffix, maxWidth)
        // leave two spaces open on the left so there's somewhere to park the cursor
        assert(expected == rendered)
        assert(rendered.length == maxWidth)
        rendered
      }
      test("simple") - check(
        "PREFIX",
        "TITLE",
        "SUFFIX",
        60,
        expected = "  PREFIX ==================== TITLE ================= SUFFIX"
      )

      test("short") - check(
        "PREFIX",
        "TITLE",
        "SUFFIX",
        40,
        expected = "  PREFIX ========== TITLE ======= SUFFIX"
      )

      test("shorter") - check(
        "PREFIX",
        "TITLE",
        "SUFFIX",
        25,
        expected = "  PREFIX ==...==== SUFFIX"
      )

      test("truncateTitle") - check(
        "PREFIX",
        "TITLE_ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "SUFFIX",
        60,
        expected = "  PREFIX ====== TITLE_ABCDEF...OPQRSTUVWXYZ ========= SUFFIX"
      )

      test("asymmetricTruncateTitle") - check(
        "PREFIX_LONG",
        "TITLE_ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "SUFFIX",
        60,
        expected = "  PREFIX_LONG = TITLE_A...TUVWXYZ =================== SUFFIX"
      )
    }
  }
}
