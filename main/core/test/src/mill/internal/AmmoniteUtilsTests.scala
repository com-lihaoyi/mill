package mill.internal

import utest._

object AmmoniteUtilsTests extends TestSuite {
  val tests = Tests {
    test("normalizeAmmoniteImportPath") {
      def normalize(s: String): String =
        AmmoniteUtils.normalizeAmmoniteImportPath(s.split('.').toIndexedSeq).mkString(".")
      test("should normalize classes compiled from multiple scripts") {
        val input1 = "ammonite.$file.e.$up.a.inputA"
        val input2 = "ammonite.$file.a.inputA"

        val result1 = normalize(input1)
        val result2 = normalize(input2)
        val expected = "ammonite.$file.a.inputA"

        assert(result1 == expected)
        assert(result2 == expected)
      }
      test("should normalize imports") {
        val input = "ammonite.$file.e.^.a.inputA"

        val result = normalize(input)
        val expected = "ammonite.$file.a.inputA"

        assert(result == expected)
      }
      test("should handle classes in higher level than top level") {
        val input1 = "ammonite.$file.^.build"
        val input2 = "ammonite.$file.$up.build"

        val result1 = normalize(input1)
        val result2 = normalize(input2)
        val expected = "ammonite.$file.$up.build"

        assert(result1 == expected)
        assert(result2 == expected)
      }
      test("complex") {
        val input = "ammonite.$file.$up.^.a.^.build"
        val result = normalize(input)
        val expected = "ammonite.$file.$up.$up.build"

        assert(result == expected)
      }
      test("should remove companion objects") {
        val input = "ammonite.$file.a.inputA$"
        val result = normalize(input)
        val expected = "ammonite.$file.a.inputA"

        assert(result == expected)
      }
      test("should remove internal classes") {
        val input = "ammonite.$file.build$module$"
        val result = normalize(input)
        val expected = "ammonite.$file.build"

        assert(result == expected)
      }
      test("should handle special symbols") {
        val input = "ammonite.$file.-#!|\\?+*<→:&>%=~.inputSymbols"
        val result = normalize(input)
        val expected = "ammonite.$file.$minus$hash$bang$bar$bslash$qmark$plus$times$less$u2192$colon$amp$greater$percent$eq$tilde.inputSymbols"

        assert(result == expected)
      }
    }
  }
}
