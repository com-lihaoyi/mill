package mill.internal

import utest._

object NormalizeImportPathsTests extends TestSuite {
  val tests = Tests {
    test("normalizeAmmoniteImportPath") {
      def normalize(s: String): String = NormalizeImportPaths.normalizeImportPath(s)
      test("should normalize classes compiled from multiple scripts") {
        val input1 = "millbuild.e.$up.a.inputA"
        val input2 = "millbuild.a.inputA"

        val result1 = normalize(input1)
        val result2 = normalize(input2)
        val expected = "millbuild.a.inputA"

        assert(result1 == expected)
        assert(result2 == expected)
      }
      test("should normalize imports") {
        val input = "millbuild.e.^.a.inputA"

        val result = normalize(input)
        val expected = "millbuild.a.inputA"

        assert(result == expected)
      }
      test("should handle classes in higher level than top level") {
        val input1 = "millbuild.^.build"
        val input2 = "millbuild.$up.build"

        val result1 = normalize(input1)
        val result2 = normalize(input2)
        val expected = "millbuild.$up.build"

        assert(result1 == expected)
        assert(result2 == expected)
      }
      test("complex") {
        val input = "millbuild.$up.^.a.^.build"
        val result = normalize(input)
        val expected = "millbuild.$up.$up.build"

        assert(result == expected)
      }
      test("should remove companion objects") {
        val input = "millbuild.a.inputA$"
        val result = normalize(input)
        val expected = "millbuild.a.inputA"

        assert(result == expected)
      }
      test("should remove internal classes") {
        val input = "millbuild.build$module$"
        val result = normalize(input)
        val expected = "millbuild.build"

        assert(result == expected)
      }
      test("should handle special symbols") {
        val input = "millbuild.-#!|\\?+*<→:&>%=~.inputSymbols"
        val result = normalize(input)
        val expected =
          "millbuild.$minus$hash$bang$bar$bslash$qmark$plus$times$less$u2192$colon$amp$greater$percent$eq$tilde.inputSymbols"

        assert(result == expected)
      }
      test("should handle special symbols in last file while removing inner classes") {
        val input = "millbuild.before$minusplus$something$minusafter"
        val result = normalize(input)
        val expected = "millbuild.before$minusplus"

        assert(result == expected)
      }
    }
  }
}
