package mill.javalib

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import mill.api.Discover
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import utest.*

object JavaTabsErrorFormattingTests extends TestSuite {

  object TabsJava extends TestRootModule {
    object core extends JavaModule
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "error-formatting-tabs-java"

  val tests: Tests = Tests {
    test("javaTabsErrorRange") {
      val errBuffer = new ByteArrayOutputStream()
      UnitTester(
        TabsJava,
        sourceRoot = resourcePath,
        outStream = new PrintStream(new ByteArrayOutputStream()),
        errStream = new PrintStream(errBuffer, true)
      ).scoped { eval =>
        val Left(_) = eval.apply(TabsJava.core.compile).runtimeChecked
        val errLines = fansi.Str(errBuffer.toString()).plainText
          .linesIterator
          .toSeq

        assertGoldenLiteral(
          errLines,
          List(
            "compiling 1 Java source to out/core/compile.dest/classes ...",
            "[error] core/src/Core.java:5:11",
            "\t\tint i = 12345.6;",
            "\t\t        ^^^^^^^",
            "incompatible types: possible lossy conversion from double to int",
            "",
            "[error] core/src/Core.java:6:13",
            "    int j = 12345.6;",
            "            ^^^^^^^",
            "incompatible types: possible lossy conversion from double to int",
            "",
            "[error] core.compile task failed"
          )
        )
      }
    }
  }
}
