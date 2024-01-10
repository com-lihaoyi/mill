package mill.eval

import utest._

object EvaluatorPathsTests extends TestSuite {

  override def tests: Tests = Tests {
    "sanitizedPathSegment" - {
      "WindowsReservedNames" - {
        val replace = Seq(
          "com1.json" -> "com1~.json",
          "LPT¹" -> "LPT¹~"
        )
        val noReplace = Seq(
          "con10.json"
        )
        for {
          (segment, result) <- replace ++ noReplace.map(s => (s, s))
        } yield {
          EvaluatorPaths.sanitizePathSegment(segment).toString ==> result
          (segment, result)
        }
      }
    }
  }
}
