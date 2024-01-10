package mill.eval

import utest._

object EvaluatorPathsTests extends TestSuite {

  override def tests: Tests = Tests {
    "sanitizedPathSegment" - {
      "WindowsReservedNames" - {
        val replace = Seq(
          "com1.json" -> "com1_.json",
          "LPT¹" -> "LPT¹_"
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
