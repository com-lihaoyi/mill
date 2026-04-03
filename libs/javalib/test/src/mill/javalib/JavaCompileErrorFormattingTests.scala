package mill.javalib

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import mill.*
import mill.api.Discover
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import utest.*

object JavaCompileErrorFormattingTests extends TestSuite {

  object JavaCompileErrorFormatting extends TestRootModule {
    object core extends JavaModule {
      def javacOptions = Seq("-Xlint:unchecked", "-Werror")
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "compile-error-formatting-java"

  private def checkLines(caseName: String): Seq[String] = {
    val errBuffer = new ByteArrayOutputStream()
    UnitTester(
      JavaCompileErrorFormatting,
      sourceRoot = resourcePath / caseName,
      outStream = new PrintStream(errBuffer, true),
      errStream = new PrintStream(errBuffer, true)
    ).scoped { eval =>
      val res = eval.apply(JavaCompileErrorFormatting.core.compile).runtimeChecked
      assert(res.isLeft)
      fansi.Str(errBuffer.toString).plainText.linesIterator.toSeq
    }
  }

  val tests: Tests = Tests {
    test("javaTypeUnchecked") {
      assertGoldenLiteral(
        checkLines("java-type-unchecked"),
        List(
          "compiling 1 Java source to out/core/compile.dest/classes ...",
          "[warn] core/src/Foo.java:6:22",
          "        return (T[]) obj;",
          "                     ^^^",
          "unchecked cast",
          "  required: T[]",
          "  found:    java.lang.Object",
          "",
          "[error] core/src/Foo.java",
          "",
          "",
          "warnings found and -Werror specified",
          "",
          "[error] core.compile task failed"
        )
      )
    }

    test("javaTypeMismatch") {
      assertGoldenLiteral(
        checkLines("java-type-mismatch"),
        List(
          "compiling 1 Java source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.java:5:17",
          "        int x = \"hello\";",
          "                ^^^^^^^",
          "incompatible types: java.lang.String cannot be converted to int",
          "",
          "[error] core.compile task failed"
        )
      )
    }

    test("javaTypeMethod") {
      assertGoldenLiteral(
        checkLines("java-type-method"),
        List(
          "compiling 1 Java source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.java:6:10",
          "        s.nonExistentMethod();",
          "         ^^^^^^^^^^^^^^^^^^",
          "cannot find symbol",
          "  symbol:   method nonExistentMethod()",
          "  location: variable s of type java.lang.String",
          "",
          "[error] core.compile task failed"
        )
      )
    }

    test("javaTypeVariable") {
      assertGoldenLiteral(
        checkLines("java-type-variable"),
        List(
          "compiling 1 Java source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.java:5:17",
          "        int x = undefinedVariable + 1;",
          "                ^^^^^^^^^^^^^^^^^",
          "cannot find symbol",
          "  symbol:   variable undefinedVariable",
          "  location: class javaTypeVariable.Foo",
          "",
          "[error] core.compile task failed"
        )
      )
    }

    test("javaParseSemicolon") {
      assertGoldenLiteral(
        checkLines("java-parse-semicolon"),
        List(
          "compiling 1 Java source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.java:5:18",
          "        int x = 1",
          "                 ^",
          "';' expected",
          "",
          "[error] core.compile task failed"
        )
      )
    }

    test("javaParseString") {
      assertGoldenLiteral(
        checkLines("java-parse-string"),
        List(
          "compiling 1 Java source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.java:5:20",
          "        String s = \"hello world",
          "                   ^",
          "unclosed string literal",
          "",
          "[error] core.compile task failed"
        )
      )
    }

    test("javaParseToplevel") {
      assertGoldenLiteral(
        checkLines("java-parse-toplevel"),
        List(
          "compiling 1 Java source to out/core/compile.dest/classes ...",
          "[error] core/src/Foo.java:3:1",
          "int x = 1;",
          "^",
          "unnamed classes are a preview feature and are disabled by default.",
          "  (use --enable-preview to enable unnamed classes)",
          "",
          "[error] core/src/Foo.java:1:1",
          "package javaParseToplevel;",
          "^",
          "unnamed class should not have package declaration",
          "",
          "[error] core.compile task failed"
        )
      )
    }
  }
}
