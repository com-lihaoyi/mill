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

  private def assertConsecutiveLines(err: String, expected: Seq[String]): Unit = {
    val lines = err.linesIterator.toVector
    val found = lines.indices.exists { start =>
      expected.indices.forall { i =>
        start + i < lines.length && lines(start + i).contains(expected(i))
      }
    }
    if (!found) {
      sys.error(
        s"Expected consecutive lines not found:\n${expected.mkString("\n")}\n\nIn output:\n$err"
      )
    }
  }

  private def check(caseName: String, expected: Seq[String]): Unit = {
    val errBuffer = new ByteArrayOutputStream()
    UnitTester(
      ScalaCompileErrorFormatting,
      sourceRoot = resourcePath / caseName,
      outStream = new PrintStream(new ByteArrayOutputStream()),
      errStream = new PrintStream(errBuffer, true)
    ).scoped { eval =>
      val Left(ExecResult.Failure(msg = "Compilation failed")) =
        eval.apply(ScalaCompileErrorFormatting.core.compile).runtimeChecked
      val err = fansi.Str(errBuffer.toString).plainText
      assertConsecutiveLines(err, expected)
    }
  }

  val tests: Tests = Tests {
    test("scalaTypeMismatch") {
      check(
        "scala-type-mismatch",
        Seq(
          "[error] core/src/Foo.scala:5:18",
          "    val x: Int = \"hello\"",
          "                 ^^^^^^^",
          "Found:    (\"hello\" : String)",
          "Required: Int"
        )
      )
    }

    test("scalaTypeMethod") {
      check(
        "scala-type-method",
        Seq(
          "[error] core/src/Foo.scala:6:7",
          "    s.nonExistentMethod()",
          "      ^^^^^^^^^^^^^^^^^",
          "value nonExistentMethod is not a member of String"
        )
      )
    }

    test("scalaTypeVariable") {
      check(
        "scala-type-variable",
        Seq(
          "[error] core/src/Foo.scala:5:13",
          "    val x = undefinedVariable + 1",
          "            ^^^^^^^^^^^^^^^^^",
          "Not found: undefinedVariable"
        )
      )
    }

    test("scalaParseSemicolon") {
      check(
        "scala-parse-semicolon",
        Seq(
          "[error] core/src/Foo.scala:6:15",
          "    val y = x +",
          "              ^",
          "end of statement expected but identifier found"
        )
      )
    }

    test("scalaParseString") {
      check(
        "scala-parse-string",
        Seq(
          "[error] core/src/Foo.scala:5:13",
          "    val s = \"hello world",
          "            ^",
          "unclosed string literal"
        )
      )
    }

    test("scalaParseToplevel") {
      check(
        "scala-parse-toplevel",
        Seq(
          "[error] core/src/Foo.scala:6:7",
          "class class Foo {",
          "      ^^^^^",
          "an identifier expected, but 'class' found"
        )
      )
    }
  }
}
