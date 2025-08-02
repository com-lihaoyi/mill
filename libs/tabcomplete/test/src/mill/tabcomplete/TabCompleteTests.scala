package mill.tabcomplete

import mill.Task
import mill.api.{Cross, Discover, Module}
import mill.internal.MillCliConfig
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object TabCompleteTests extends TestSuite {

  object mainModule extends TestRootModule {
    lazy val millDiscover = Discover[this.type]
    def task1 = Task { 123 }
    object foo extends Module
    object bar extends Module {
      def task2 = Task { 456 }

    }
    object qux extends Cross[QuxModule](12, 34, 56)
    trait QuxModule extends Cross.Module[Int] {
      def task3 = Task { 789 }
    }
  }
  override def tests: Tests = Tests {

    val outStream = new ByteArrayOutputStream()
    val errStream = new ByteArrayOutputStream()

    def evalComplete(s: String*) = {
      UnitTester(
        mainModule,
        null,
        outStream = new PrintStream(outStream),
        errStream = new PrintStream(errStream)
      ).scoped { tester =>
        os.write(tester.evaluator.workspace / "file1.txt", "")
        os.write(tester.evaluator.workspace / "file2.txt", "")
        tester.evaluator.evaluate(Seq("mill.tabcomplete.TabCompleteModule/complete") ++ s).get
      }
      outStream.toString.linesIterator.toSeq
    }

    test("empty-bash") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", ""),
        List("bar", "foo", "qux", "task1")
      )
    }
    test("empty-zsh") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill"),
        List("bar", "foo", "qux", "task1")
      )
    }
    test("task") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "t"),
        List("task1")
      )
    }
    test("firstTask") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "t", "bar.task2"),
        List("task1")
      )
    }

    test("secondNonTask") - {
      assertGoldenLiteral(
        evalComplete("2", "./mill", "bar.task2", "f"),
        List("file2.txt", "file1.txt")
      )
    }
    test("secondNonTaskEmpty") - {
      assertGoldenLiteral(
        evalComplete("2", "./mill", "bar.task2", ""),
        List("file2.txt", "file1.txt", "out")
      )
    }

    test("module") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "fo"),
        List("foo")
      )
    }

    test("exactModule") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "bar"),
        List("bar", "bar.task2")
      )
    }

    test("nested") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "bar."),
        List("bar.task2")
      )
    }

    test("cross") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "qux["),
        List("qux[12]", "qux[34]", "qux[56]")
      )
    }

    test("cross2") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "qux"),
        List("qux", "qux[12]", "qux[34]", "qux[56]")
      )
    }

    test("crossPartial") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "qux[1"),
        List("qux[12]")
      )
    }

    test("crossNested") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "qux[12]"),
        List("qux[12].task3")
      )
    }

    test("crossNestedSlashed") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "qux\\[12\\]"),
        List("qux[12].task3")
      )
    }
    test("crossNestedSingleQuoted") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "'qux[12]"),
        List("qux[12].task3")
      )
    }
    test("crossNestedDoubleQuoted") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "\"qux[12]"),
        List("qux[12].task3")
      )
    }

    test("crossComplete") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "qux[12].task3"),
        List("qux[12].task3")
      )

    }
    test("shortflags") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "-"),
        List("-v", "-b", "-d", "-k", "-D", "-j", "-i", "-w", "-h", "-s")
      )
    }
    test("emptyAfterFlag") - {
      assertGoldenLiteral(
        evalComplete("2", "./mill", "-v"),
        List("bar", "foo", "qux", "task1")
      )

    }
    test("filterAfterFlag") - {
      assertGoldenLiteral(
        evalComplete("2", "./mill", "-v", "f"),
        List("foo")
      )
    }
    test("filterAfterFlagAfterTask") - {
      assertGoldenLiteral(
        evalComplete("3", "./mill", "-v", "task1", "f"),
        Seq("file2.txt", "file1.txt")
      )
    }
    test("longflags") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "--"),
        List(
          "--no-daemon",
          "--ticker",
          "--keep-going",
          "--interactive",
          "--help",
          "--help-advanced",
          "--watch",
          "--color",
          "--meta-level",
          "--allow-positional",
          "--bsp",
          "--bsp-install",
          "--bsp-watch",
          "--no-build-lock",
          "--no-wait-for-build-lock",
          "--offline",
          "--no-filesystem-checker",
          "--tab-complete",
          "--home",
          "--repl",
          "--no-server",
          "--silent",
          "--disable-prompt",
          "--enable-ticker",
          "--disable-ticker"
        )
      )
    }
    test("longflagsfiltered") - {
      assertGoldenLiteral(
        evalComplete("1", "./mill", "--h"),
        List("--help", "--help-advanced", "--home")
      )
    }
  }
}
