package mill.scalalib

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import mill.api.Discover
import mill.api.ExecResult
import mill.testkit.TestRootModule
import mill.testkit.UnitTester
import mill.util.TokenReaders.*
import utest.*

object ScalaTabsErrorFormattingTests extends TestSuite {

  object TabsScala extends TestRootModule {
    object core extends ScalaModule {
      def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "error-formatting-tabs-scala"

  val tests: Tests = Tests {
    test("scalaTabsErrorRange") {
      val errBuffer = new ByteArrayOutputStream()
      UnitTester(
        TabsScala,
        sourceRoot = resourcePath,
        outStream = new PrintStream(new ByteArrayOutputStream()),
        errStream = new PrintStream(errBuffer, true)
      ).scoped { eval =>
        val Left(ExecResult.Failure(msg = "Compilation failed")) =
          eval.apply(TabsScala.core.compile).runtimeChecked

        val normalizedErrLines = fansi.Str(errBuffer.toString).plainText.linesIterator.toSeq
          .map(_.replaceAll(raw"core/src/Main\.scala:\d+:", "core/src/Main.scala:<line>:"))
          .filterNot(_ == "Compiling compiler interface...")

        assertGoldenLiteral(
          normalizedErrLines,
          List(
            "compiling 1 Scala source to out/core/compile.dest/classes ...",
            "[error] core/src/Main.scala:<line>:18",
            """		val bad: Int = "hello"""",
            "\t\t               ^",
            "type mismatch;",
            """ found   : String("hello")""",
            " required: Int",
            "",
            "[error] one error found",
            "[error] core.compile task failed"
          )
        )
      }
    }
  }
}
