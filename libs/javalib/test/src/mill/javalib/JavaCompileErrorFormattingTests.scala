package mill.javalib

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import mill.api.Discover
import mill.*
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import utest.*

object JavaCompileErrorFormattingTests extends TestSuite {

  object JavaCompileErrorFormatting extends TestRootModule {
    object core extends JavaModule
    lazy val millDiscover = Discover[this.type]
  }
  object JavaCompileErrorFormattingUnchecked extends TestRootModule {
    object core extends JavaModule {
      def javacOptions = Seq("-Xlint:unchecked", "-Werror")
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "compile-error-formatting-java"

  private def containsConsecutiveLines(err: String, expected: Seq[String]): Boolean = {
    val lines = err.linesIterator.toVector
    lines.indices.exists { start =>
      expected.indices.forall { i =>
        start + i < lines.length && lines(start + i).contains(expected(i))
      }
    }
  }

  private def assertConsecutiveLines(err: String, expected: Seq[String]): Unit = {
    if (!containsConsecutiveLines(err, expected)) {
      sys.error(
        s"Expected consecutive lines not found:\n${expected.mkString("\n")}\n\nIn output:\n$err"
      )
    }
  }

  private def assertContainsAll(err: String, expected: Seq[String]): Unit = {
    val missing = expected.filterNot(err.contains)
    if (missing.nonEmpty) {
      sys.error(
        s"Expected lines not found:\n${missing.mkString("\n")}\n\nIn output:\n$err"
      )
    }
  }

  private def check(caseName: String, expected: Seq[String], requireFailure: Boolean = true): Unit = {
    val errBuffer = new ByteArrayOutputStream()
    UnitTester(
      JavaCompileErrorFormatting,
      sourceRoot = resourcePath / caseName,
      outStream = new PrintStream(new ByteArrayOutputStream()),
      errStream = new PrintStream(errBuffer, true)
    ).scoped { eval =>
      val res = eval.apply(JavaCompileErrorFormatting.core.compile).runtimeChecked
      if (requireFailure) assert(res.isLeft)
      val err = fansi.Str(errBuffer.toString).plainText
      assertConsecutiveLines(err, expected)
    }
  }

  val tests: Tests = Tests {
    test("javaTypeUnchecked") {
      val errBuffer = new ByteArrayOutputStream()
      UnitTester(
        JavaCompileErrorFormattingUnchecked,
        sourceRoot = resourcePath / "java-type-unchecked",
        outStream = new PrintStream(new ByteArrayOutputStream()),
        errStream = new PrintStream(errBuffer, true)
      ).scoped { eval =>
        val res = eval.apply(JavaCompileErrorFormattingUnchecked.core.compile).runtimeChecked
        assert(res.isLeft)
        val err = fansi.Str(errBuffer.toString).plainText
        assertConsecutiveLines(
          err,
          Seq(
            "[warn] core/src/Foo.java:6:22",
            "        return (T[]) obj;",
            "                     ^^^",
            "unchecked cast"
          )
        )
        assertContainsAll(
          err,
          Seq(
            "warnings found and -Werror specified",
            "[error] core.compile task failed"
          )
        )
      }
    }

    test("javaTypeMismatch") {
      check(
        "java-type-mismatch",
        Seq(
          "[error] core/src/Foo.java:5:17",
          "        int x = \"hello\";",
          "                ^^^^^^^",
          "incompatible types: java.lang.String cannot be converted to int"
        )
      )
    }

    test("javaTypeMethod") {
      check(
        "java-type-method",
        Seq(
          "[error] core/src/Foo.java:6:10",
          "        s.nonExistentMethod();",
          "         ^^^^^^^^^^^^^^^^^^",
          "cannot find symbol"
        )
      )
    }

    test("javaTypeVariable") {
      check(
        "java-type-variable",
        Seq(
          "[error] core/src/Foo.java:5:17",
          "        int x = undefinedVariable + 1;",
          "                ^^^^^^^^^^^^^^^^^",
          "cannot find symbol"
        )
      )
    }

    test("javaParseSemicolon") {
      check(
        "java-parse-semicolon",
        Seq(
          "[error] core/src/Foo.java:5:18",
          "        int x = 1",
          "                 ^",
          "';' expected"
        )
      )
    }

    test("javaParseString") {
      check(
        "java-parse-string",
        Seq(
          "[error] core/src/Foo.java:5:20",
          "        String s = \"hello world",
          "                   ^",
          "unclosed string literal"
        )
      )
    }

    test("javaParseToplevel") {
      check(
        "java-parse-toplevel",
        Seq(
          "[error] core/src/Foo.java:3:1",
          "int x = 1;",
          "^",
          "unnamed classes are a preview feature and are disabled by default."
        )
      )
    }
  }
}
