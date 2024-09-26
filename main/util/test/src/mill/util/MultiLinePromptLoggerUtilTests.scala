package mill.util

import utest._

import scala.collection.immutable.SortedMap
import MultilinePromptLoggerUtil._
object MultiLinePromptLoggerUtilTests extends TestSuite {

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
            val found = lastIndexOfNewline(sample, start, len)
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
    test("renderHeader") {

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

    test("renderPrompt") {
      import MultiLinePromptLogger._
      val now = System.currentTimeMillis()
      def renderPromptTest(
          interactive: Boolean,
          titleText: String = "__.compile"
      )(statuses: (Int, Status)*) = {
        renderPrompt(
          consoleWidth = 60,
          consoleHeight = 20,
          now = now,
          startTimeMillis = now - 1337000,
          headerPrefix = "123/456",
          titleText = titleText,
          statuses = SortedMap(statuses.map{case (k, v) => (k.toString, v)}: _*),
          interactive = interactive
        )

      }
      test("simple") {
        val rendered = renderPromptTest(interactive = true)(
          0 -> Status(Some(StatusEntry("hello", now - 1000)), 0, None),
          1 -> Status(Some(StatusEntry("world", now - 2000)), 0, None)
        )
        val expected = List(
          "  123/456 =============== __.compile ================ 1337s",
          "hello 1s",
          "world 2s"
        )
        assert(rendered == expected)
      }

      test("maxWithoutTruncation") {
        val rendered =
          renderPromptTest(interactive = true, titleText = "__.compile.abcdefghijklmn")(
            0 -> Status(
              Some(StatusEntry(
                "#1 hello1234567890abcefghijklmnopqrstuvwxyz1234567890123",
                now - 1000
              )),
              0,
              None
            ),
            1 -> Status(Some(StatusEntry("#2 world", now - 2000)), 0, None),
            2 -> Status(Some(StatusEntry("#3 i am cow", now - 3000)), 0, None),
            3 -> Status(Some(StatusEntry("#4 hear me moo", now - 4000)), 0, None),
            4 -> Status(Some(StatusEntry("#5 i weigh twice as much as you", now - 5000)), 0, None)
          )

        val expected = List(
          "  123/456 ======== __.compile.abcdefghijklmn ======== 1337s",
          "#1 hello1234567890abcefghijklmnopqrstuvwxyz1234567890123 1s",
          "#2 world 2s",
          "#3 i am cow 3s",
          "#4 hear me moo 4s",
          "#5 i weigh twice as much as you 5s"
        )
        assert(rendered == expected)
      }
      test("minAfterTruncation") {
        val rendered =
          renderPromptTest(interactive = true, titleText = "__.compile.abcdefghijklmno")(
            0 -> Status(
              Some(StatusEntry(
                "#1 hello1234567890abcefghijklmnopqrstuvwxyz12345678901234",
                now - 1000
              )),
              0,
              None
            ),
            1 -> Status(Some(StatusEntry("#2 world", now - 2000)), 0, None),
            2 -> Status(Some(StatusEntry("#3 i am cow", now - 3000)), 0, None),
            3 -> Status(Some(StatusEntry("#4 hear me moo", now - 4000)), 0, None),
            4 -> Status(Some(StatusEntry("#5 i weigh twice as much as you", now - 5000)), 0, None),
            5 -> Status(
              Some(StatusEntry("#6 and I look good on the barbecue", now - 6000)),
              0,
              None
            )
          )

        val expected = List(
          "  123/456 ======= __.compile....efghijklmno ========= 1337s",
          "#1 hello1234567890abcefghijk...pqrstuvwxyz12345678901234 1s",
          "#2 world 2s",
          "#3 i am cow 3s",
          "#4 hear me moo 4s",
          "... and 2 more threads"
        )
        assert(rendered == expected)
      }

      test("truncated") {
        val rendered = renderPromptTest(
          interactive = true,
          titleText = "__.compile.abcdefghijklmnopqrstuvwxyz1234567890"
        )(
          0 -> Status(
            Some(StatusEntry("#1 hello1234567890abcefghijklmnopqrstuvwxyz" * 3, now - 1000)),
            0,
            None
          ),
          1 -> Status(Some(StatusEntry("#2 world", now - 2000)), 0, None),
          2 -> Status(Some(StatusEntry("#3 i am cow", now - 3000)), 0, None),
          3 -> Status(Some(StatusEntry("#4 hear me moo", now - 4000)), 0, None),
          4 -> Status(Some(StatusEntry("#5 i weigh twice as much as you", now - 5000)), 0, None),
          5 -> Status(Some(StatusEntry("#6 and i look good on the barbecue", now - 6000)), 0, None),
          6 -> Status(
            Some(StatusEntry("#7 yoghurt curds cream cheese and butter", now - 7000)),
            0,
            None
          )
        )
        val expected = List(
          "  123/456  __.compile....z1234567890 ================ 1337s",
          "#1 hello1234567890abcefghijk...abcefghijklmnopqrstuvwxyz 1s",
          "#2 world 2s",
          "#3 i am cow 3s",
          "#4 hear me moo 4s",
          "... and 3 more threads"
        )
        assert(rendered == expected)
      }

      test("removalDelay") {
        val rendered = renderPromptTest(
          interactive = true,
          titleText = "__.compile.abcdefghijklmnopqrstuvwxyz1234567890"
        )(
          // Not yet removed, should be shown
          0 -> Status(
            Some(StatusEntry("#1 hello1234567890abcefghijklmnopqrstuvwxyz" * 3, now - 1000)),
            0,
            None
          ),
          // This is removed but are still within the transition window, so still shown
          2 -> Status(
            None,
            now - statusRemovalHideDelayMillis + 1,
            Some(StatusEntry("#3 i am cow", now - 3000))
          ),
          // Removed but already outside the `statusRemovalDelayMillis` window, not shown, but not
          // yet removed, so rendered as blank lines to prevent terminal jumping around too much
          3 -> Status(
            None,
            now - statusRemovalRemoveDelayMillis + 1,
            Some(StatusEntry("#4 hear me moo", now - 4000))
          ),
          4 -> Status(
            None,
            now - statusRemovalRemoveDelayMillis + 1,
            Some(StatusEntry("#5 i weigh twice", now - 5000))
          ),
          5 -> Status(
            None,
            now - statusRemovalRemoveDelayMillis + 1,
            Some(StatusEntry("#6 as much as you", now - 6000))
          ),
          // This one would be rendered as a blank line, but because of the max prompt height
          // controlled by the `consoleHeight` it ends up being silently truncated
          6 -> Status(
            None,
            now - statusRemovalRemoveDelayMillis + 1,
            Some(StatusEntry("#7 and I look good on the barbecue", now - 7000))
          )
        )

        val expected = List(
          "  123/456  __.compile....z1234567890 ================ 1337s",
          "#1 hello1234567890abcefghijk...abcefghijklmnopqrstuvwxyz 1s",
          "#3 i am cow 3s",
          "",
          "",
          ""
        )

        assert(rendered == expected)
      }
      test("removalFinal") {
        val rendered = renderPromptTest(
          interactive = true,
          titleText = "__.compile.abcdefghijklmnopqrstuvwxyz1234567890"
        )(
          // Not yet removed, should be shown
          0 -> Status(
            Some(StatusEntry("#1 hello1234567890abcefghijklmnopqrstuvwxyz" * 3, now - 1000)),
            0,
            None
          ),
          // This is removed a long time ago, so it is totally removed
          1 -> Status(
            None,
            now - statusRemovalRemoveDelayMillis - 1,
            Some(StatusEntry("#2 world", now - 2000))
          ),
          // This is removed but are still within the transition window, so still shown
          2 -> Status(
            None,
            now - statusRemovalHideDelayMillis + 1,
            Some(StatusEntry("#3 i am cow", now - 3000))
          )
        )

        val expected = List(
          "  123/456  __.compile....z1234567890 ================ 1337s",
          "#1 hello1234567890abcefghijk...abcefghijklmnopqrstuvwxyz 1s",
          "#3 i am cow 3s"
        )

        assert(rendered == expected)
      }

      test("nonInteractive") {
        val rendered = renderPromptTest(
          interactive = false,
          titleText = "__.compile.abcdefghijklmnopqrstuvwxyz1234567890"
        )(
          // Not yet removed, should be shown
          0 -> Status(
            Some(StatusEntry("#1 hello1234567890abcefghijklmnopqrstuvwxyz" * 3, now - 1000)),
            0,
            None
          ),
          // These are removed but are still within the `statusRemovalDelayMillis` window, so still shown
          1 -> Status(
            None,
            now - statusRemovalHideDelayMillis + 1,
            Some(StatusEntry("#2 world", now - 2000))
          ),
          2 -> Status(
            None,
            now - statusRemovalHideDelayMillis + 1,
            Some(StatusEntry("#3 i am cow", now - 3000))
          ),
          // Removed but already outside the `statusRemovalDelayMillis` window, not shown, but not
          // yet removed, so rendered as blank lines to prevent terminal jumping around too much
          3 -> Status(
            None,
            now - statusRemovalRemoveDelayMillis - 1,
            Some(StatusEntry("#4 hear me moo", now - 4000))
          ),
          4 -> Status(
            None,
            now - statusRemovalRemoveDelayMillis - 1,
            Some(StatusEntry("#5 i weigh twice", now - 5000))
          ),
          5 -> Status(
            None,
            now - statusRemovalRemoveDelayMillis - 1,
            Some(StatusEntry("#6 as much as you", now - 6000))
          )
        )

        // Make sure the non-interactive prompt does not show the blank lines,
        // and it contains a footer line to mark the end of the prompt in logs
        val expected = List(
          "123/456  __.compile.ab...xyz1234567890 ============== 1337s",
          "#1 hello1234567890abcefghijk...abcefghijklmnopqrstuvwxyz 1s",
          "#2 world 2s",
          "#3 i am cow 3s",
          "==========================================================="
        )
        assert(rendered == expected)
      }
    }
  }
}
