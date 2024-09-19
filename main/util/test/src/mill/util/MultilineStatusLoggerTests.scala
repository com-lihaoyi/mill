package mill.util

import utest._

object MultilineStatusLoggerTests extends TestSuite {

  val tests = Tests {
    test {
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

        Array('\n', '\n', '\n', '\n'),
      )

      for(sample <- allSampleByteArrays){
        for(start <- Range(0, sample.length)){
          for(len <- Range(0, sample.length - start)){
            val found = MultilinePromptLogger.lastIndexOfNewline(sample, start, len)
            val expected0 = sample.slice(start, start + len).lastIndexOf('\n')
            val expected = expected0 + start
            def assertMsg = s"found:$found, expected$expected, sample:${sample.toSeq}, start:$start, len:$len"
            if (expected0 == -1) Predef.assert(found == -1, assertMsg)
            else Predef.assert(found == expected, assertMsg)
          }
        }
      }
    }

  }
}
