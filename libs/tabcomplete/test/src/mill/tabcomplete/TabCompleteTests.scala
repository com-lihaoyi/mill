package mill.tabcomplete

import mill.Task
import mill.api.{Cross, Discover, Module}
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object TabCompleteTests extends TestSuite {

  object mainModule extends TestRootModule {
    lazy val millDiscover = Discover[this.type]
    def task1(argA: String = "", argB2: Int = 0) = Task.Command { 123 }
    object foo extends Module
    object bar extends Module {
      def task2(argA3: String, argB4: Int) = Task.Command { 456 }

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
      outStream.toString.linesIterator.toSet
    }

    test("tasks") {
      test("empty-bash") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", ""),
          Set("bar", "foo", "qux", "task1")
        )
      }
      test("empty-zsh") {
        assertGoldenLiteral(
          evalComplete("1", "./mill"),
          Set("bar", "foo", "qux", "task1")
        )
      }
      test("task") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "t"),
          Set("task1")
        )
      }
      test("firstTask") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "t", "bar.task2"),
          Set("task1")
        )
      }

      test("secondNonTask") {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "bar.task2", "f"),
          Set("file2.txt", "file1.txt")
        )
      }
      test("secondNonTaskEmpty") {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "bar.task2", ""),
          Set("file2.txt", "file1.txt", "out")
        )
      }

      test("module") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "fo"),
          Set("foo")
        )
      }

      test("exactModule") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "bar"),
          Set("bar", "bar.task2")
        )
      }

      test("nested") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "bar."),
          Set("bar.task2")
        )
      }

      test("cross") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "qux["),
          Set("qux[12]", "qux[34]", "qux[56]")
        )
      }

      test("cross2") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "qux"),
          Set("qux", "qux[12]", "qux[34]", "qux[56]")
        )
      }

      test("crossPartial") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "qux[1"),
          Set("qux[12]")
        )
      }

      test("crossNested") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "qux[12]"),
          Set("qux[12].task3")
        )
      }

      test("crossNestedSlashed") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "qux\\[12\\]"),
          Set("qux[12].task3")
        )
      }
      test("crossNestedSingleQuoted") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "'qux[12]"),
          Set("qux[12].task3")
        )
      }
      test("crossNestedDoubleQuoted") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "\"qux[12]"),
          Set("qux[12].task3")
        )
      }

      test("crossComplete") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "qux[12].task3"),
          Set("qux[12].task3")
        )

      }
    }
    test("flags") {
      test("short") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "-"),
          Set("-v", "-b", "-d", "-k", "-D", "-j", "-i", "-w", "-h", "-s")
        )
      }
      test("emptyAfterFlag") {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "-v"),
          Set("bar", "foo", "qux", "task1")
        )

      }
      test("filterAfterFlag") {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "-v", "f"),
          Set("foo")
        )
      }
      test("filterAfterFlagAfterTask") {
        assertGoldenLiteral(
          evalComplete("3", "./mill", "-v", "task1", "f"),
          Set("file2.txt", "file1.txt")
        )
      }

      test("long") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "--"),
          Set(
            "--jobs",
            "--watch",
            "--no-build-lock",
            "--interactive",
            "--repl",
            "--home",
            "--no-wait-for-build-lock",
            "--help",
            "--color",
            "--help-advanced",
            "--tab-complete",
            "--disable-ticker",
            "--offline",
            "--allow-positional",
            "--ticker",
            "--disable-prompt",
            "--no-server",
            "--enable-ticker",
            "--no-filesystem-checker",
            "--disable-callgraph",
            "--keep-going",
            "--bsp",
            "--meta-level",
            "--version",
            "--no-daemon",
            "--import",
            "--bsp-install",
            "--task",
            "--notify-watch",
            "--bsp-watch",
            "--define",
            "--bell",
            "--silent",
            "--debug"
          )
        )
      }
      test("longflagsfiltered") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "--h"),
          Set("--help", "--help-advanced", "--home")
        )
      }
      test("longFlagBrokenEarlier") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "--jo", "1", "task1"),
          Set("--jobs")
        )
      }
    }
    test("commandflags") {
      test {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "task1", "--a"),
          Set("--arg-a", "--arg-b-2")
        )
      }
      test {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "task1", "--arg-b"),
          Set("--arg-b-2")
        )
      }
      test {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "bar.task2", "--a"),
          Set("--arg-a-3", "--arg-b-4")
        )
      }
      test {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "bar.task2", "--arg-b"),
          Set("--arg-b-4")
        )
      }
    }
  }
}
