package mill.scalalib

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import mill.api.Discover
import mill.api.ExecResult
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import utest.*

object ScalaCompileErrorFormattingTests extends TestSuite {

  object ScalaCompileErrorFormatting extends TestRootModule {
    object core extends ScalaModule {
      def scalaVersion = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "compile-error-formatting-scala"

  private def checkLines(caseName: String): Seq[String] = {
    val errBuffer = new ByteArrayOutputStream()
    UnitTester(
      ScalaCompileErrorFormatting,
      sourceRoot = resourcePath / caseName,
      outStream = new PrintStream(errBuffer, true),
      errStream = new PrintStream(errBuffer, true)
    ).scoped { eval =>
      val Left(ExecResult.Failure(msg = "Compilation failed")) =
        eval.apply(ScalaCompileErrorFormatting.core.compile).runtimeChecked
      fansi.Str(errBuffer.toString).plainText.linesIterator.toSeq
    }
  }

  val tests: Tests = Tests {
    test("scalaTypeMismatch") {
      assertGoldenLiteral(
        checkLines("scala-type-mismatch"),
        List(
          "compiling 1 Scala source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.scala:5:18",
          "    val x: Int = \"hello\"",
          "                 ^^^^^^^",
          "Found:    (\"hello\" : String)",
          "Required: Int",
          "",
          "[error] one error found",
          "[error] core.compile task failed"
        )
      )
    }

    test("scalaTypeMethod") {
      assertGoldenLiteral(
        checkLines("scala-type-method"),
        List(
          "compiling 1 Scala source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.scala:6:7",
          "    s.nonExistentMethod()",
          "      ^^^^^^^^^^^^^^^^^",
          "value nonExistentMethod is not a member of String",
          "",
          "[error] one error found",
          "[error] core.compile task failed"
        )
      )
    }

    test("scalaTypeVariable") {
      assertGoldenLiteral(
        checkLines("scala-type-variable"),
        List(
          "compiling 1 Scala source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.scala:5:13",
          "    val x = undefinedVariable + 1",
          "            ^^^^^^^^^^^^^^^^^",
          "Not found: undefinedVariable",
          "",
          "[error] one error found",
          "[error] core.compile task failed"
        )
      )
    }

    test("scalaParseSemicolon") {
      assertGoldenLiteral(
        checkLines("scala-parse-semicolon"),
        List(
          "compiling 1 Scala source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.scala:6:15",
          "    val y = x +",
          "              ^",
          "end of statement expected but identifier found",
          "",
          "[error] one error found",
          "[error] core.compile task failed"
        )
      )
    }

    test("scalaParseString") {
      assertGoldenLiteral(
        checkLines("scala-parse-string"),
        List(
          "compiling 1 Scala source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.scala:5:13",
          "    val s = \"hello world",
          "            ^",
          "unclosed string literal",
          "",
          "[error] one error found",
          "[error] core.compile task failed"
        )
      )
    }

    test("scalaParseToplevel") {
      assertGoldenLiteral(
        checkLines("scala-parse-toplevel"),
        List(
          "compiling 1 Scala source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.scala:6:7",
          "class class Foo {",
          "      ^^^^^",
          "an identifier expected, but 'class' found",
          "",
          "[error] one error found",
          "[error] core.compile task failed"
        )
      )
    }
  }
}
