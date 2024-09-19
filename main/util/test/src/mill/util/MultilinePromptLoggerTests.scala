package mill.util

import utest._

import scala.collection.immutable.SortedMap

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

    test("renderPrompt"){
      import MultilinePromptLogger.{renderPrompt, Status}
      val now = System.currentTimeMillis()
      test("simple") {
        val rendered = renderPrompt(
          consoleWidth = 60,
          consoleHeight = 20,
          now = now,
          startTimeMillis = now - 1337000,
          headerPrefix = "123/456",
          titleText = "__.compile",
          statuses = SortedMap(
            0 -> Status(now - 1000, "hello", Long.MaxValue),
            1 -> Status(now - 2000, "world", Long.MaxValue),
            //          2 -> Status(now - 3000, "i am cow", Long.MaxValue),
            //          3 -> Status(now - 4000, "hear me moo", Long.MaxValue)
          )
        )
        val expected = List(
          "  123/456 =============== __.compile ================ 1337s",
          "hello 1s",
          "world 2s"
        )
        assert(rendered == expected)
      }

      test("maxWithoutTruncation") {
        val rendered = renderPrompt(
          consoleWidth = 60,
          consoleHeight = 20,
          now = now,
          startTimeMillis = now - 1337000,
          headerPrefix = "123/456",
          titleText = "__.compile.abcdefghijklmn",
          statuses = SortedMap(
            0 -> Status(now - 1000, "hello1234567890abcefghijklmnopqrstuvwxyz1234567890123456", Long.MaxValue),
            1 -> Status(now - 2000, "world", Long.MaxValue),
            2 -> Status(now - 3000, "i am cow", Long.MaxValue),
            3 -> Status(now - 4000, "hear me moo", Long.MaxValue),
          )
        )

        val expected = List(
          "  123/456 ======== __.compile.abcdefghijklmn ======== 1337s",
          "hello1234567890abcefghijklmnopqrstuvwxyz1234567890123456 1s",
          "world 2s",
          "i am cow 3s",
          "hear me moo 4s"
        )
        assert(rendered == expected)
      }
      test("minAfterTruncation") {
        val rendered = renderPrompt(
          consoleWidth = 60,
          consoleHeight = 20,
          now = now,
          startTimeMillis = now - 1337000,
          headerPrefix = "123/456",
          titleText = "__.compile.abcdefghijklmno",
          statuses = SortedMap(
            0 -> Status(now - 1000, "hello1234567890abcefghijklmnopqrstuvwxyz12345678901234567", Long.MaxValue),
            1 -> Status(now - 2000, "world", Long.MaxValue),
            2 -> Status(now - 3000, "i am cow", Long.MaxValue),
            3 -> Status(now - 4000, "hear me moo", Long.MaxValue),
            4 -> Status(now - 5000, "i weight twice as much as you", Long.MaxValue),
          )
        )

        val expected =  List(
          "  123/456 ======= __.compile....efghijklmno ========= 1337s",
          "hello1234567890abcefghijklmn...stuvwxyz12345678901234567 1s",
          "world 2s",
          "i am cow 3s",
          "hear me moo 4s",
          "... and 1 more threads"
        )
        assert(rendered == expected)
      }

      test("truncated") {
        val rendered = renderPrompt(
          consoleWidth = 60,
          consoleHeight = 20,
          now = now,
          startTimeMillis = now - 1337000,
          headerPrefix = "123/456",
          titleText = "__.compile.abcdefghijklmnopqrstuvwxyz1234567890",
          statuses = SortedMap(
            0 -> Status(now - 1000, "hello1234567890abcefghijklmnopqrstuvwxyz" * 3, Long.MaxValue),
            1 -> Status(now - 2000, "world", Long.MaxValue),
            2 -> Status(now - 3000, "i am cow", Long.MaxValue),
            3 -> Status(now - 4000, "hear me moo", Long.MaxValue),
            4 -> Status(now - 5000, "i weigh twice as much as you", Long.MaxValue),
            5 -> Status(now - 6000, "and i look good on the barbecue", Long.MaxValue),
          )
        )
        val expected = List(
          "  123/456  __.compile....z1234567890 ================ 1337s",
          "hello1234567890abcefghijklmn...abcefghijklmnopqrstuvwxyz 1s",
          "world 2s",
          "i am cow 3s",
          "hear me moo 4s",
          "... and 2 more threads"
        )
        assert(rendered == expected)
      }
    }
  }
}
