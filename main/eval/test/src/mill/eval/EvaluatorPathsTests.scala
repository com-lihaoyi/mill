package mill.eval

import utest._

object EvaluatorPathsTests extends TestSuite {

  override def tests: Tests = Tests {
    test("sanitizedPathSegment") {
      test("mask-reserved-chars-and-names") {
        val replace = Seq(
          // reserved file names under Windows
          "com1.json" -> "com1~.json",
          "LPT¹" -> "LPT¹~",
          // a unsupported character under Windows
          "a:b" -> "a$colonb",
          // do not collide with the applied `$`-masking character
          "a$colonb" -> "a$$colonb",
          // replace not just the first $
          "a$$b" -> "a$$$$b",
          // replace a forward slash,
          "a/b" -> "a$slashb"
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
