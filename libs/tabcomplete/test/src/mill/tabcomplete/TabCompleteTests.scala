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
      def task2(
          @mainargs.arg(doc = "arg a 3 docs") argA3: String,
          @mainargs.arg(doc = "arg b 4 docs") argB4: Int
      ) = Task.Command { 456 }
      def taskPositional(
          @mainargs.arg(positional = true) argA3: String,
          @mainargs.arg(positional = true) argB4: Int
      ) = Task.Command { 456 }

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
          Set("bar", "bar.task2", "bar.taskPositional")
        )
      }

      test("nested") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "bar."),
          Set("bar.task2", "bar.taskPositional")
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
          Set(
            "-b  Ring the bell once if the run completes successfully, twice if it fails.",
            "-w  Watch and re-run the given tasks when when their inputs change.",
            "-i  Run Mill in interactive mode, suitable for opening REPLs and taking user input.",
            "-v  Show mill version information and exit.",
            "-k  Continue build, even after build failures.",
            "-D  <k=v> Define (or overwrite) a system property.",
            "-d  Show debug output on STDOUT",
            "-j  <str> The number of parallel threads. It can be an integer e.g. `5`"
          )
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
            "--bsp                     Enable BSP server mode.",
            "--debug                   Show debug output on STDOUT",
            "--bell                    Ring the bell once if the run completes successfully, twice if it fails.",
            "--color                   <bool> Toggle colored output; by default enabled only if the console is interactive",
            "--no-build-lock           Evaluate tasks / commands without acquiring an exclusive lock on the Mill output directory",
            "--import                  <str> Additional ivy dependencies to load into mill, e.g. plugins.",
            "--jobs                    <str> The number of parallel threads. It can be an integer e.g. `5`",
            "--bsp-install             Create mill-bsp.json with Mill details under .bsp/",
            "--no-daemon               Run without a long-lived background daemon. Must be the first argument.",
            "--help                    Print this help message and exit.",
            "--allow-positional        Allows command args to be passed positionally without `--arg` by default",
            "--watch                   Watch and re-run the given tasks when when their inputs change.",
            "--interactive             Run Mill in interactive mode, suitable for opening REPLs and taking user input.",
            "--no-wait-for-build-lock  Do not wait for an exclusive lock on the Mill output directory to evaluate tasks / commands.",
            "--help-advanced           Print a internal or advanced command flags not intended for common usage",
            "--ticker                  <bool> Enable or disable the ticker log, which provides information on running",
            "--tab-complete            Runs Mill in tab-completion mode",
            "--meta-level              <int> Select a meta-level to run the given tasks. Level 0 is the main project in `build.mill`,",
            "--keep-going              Continue build, even after build failures.",
            "--define                  <k=v> Define (or overwrite) a system property.",
            "--notify-watch            <bool> Use filesystem based file watching instead of polling based one (defaults to true).",
            "--bsp-watch               <bool> Automatically reload the build when its sources change when running the BSP server (defaults to true).",
            "--offline                 Try to work offline.",
            "--no-filesystem-checker   Globally disables the checks that prevent you from reading and writing to disallowed ",
            "--version                 Show mill version information and exit.",
            "--task                    <str> The name or a query of the tasks(s) you want to build."
          )
        )
      }
      test("longflagsfiltered") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "--h"),
          Set(
            "--help           Print this help message and exit.",
            "--help-advanced  Print a internal or advanced command flags not intended for common usage"
          )
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
      test("required") {
        test {
          assertGoldenLiteral(
            evalComplete("2", "./mill", "task1", "--a"),
            Set("--arg-a    <str> ", "--arg-b-2  <int> ")
          )
        }
        test {
          assertGoldenLiteral(
            evalComplete("2", "./mill", "task1", "--arg-b"),
            Set("--arg-b-2")
          )
        }
      }
      test("positional") {
        test {
          assertGoldenLiteral(
            evalComplete("2", "./mill", "bar.taskPositional", "--"),
            Set()
          )
        }
        test {
          assertGoldenLiteral(
            evalComplete("2", "./mill", "bar.taskPositional", ""),
            Set("file2.txt", "file1.txt", "out")
          )
        }
      }
      test("optional") {
        test {
          assertGoldenLiteral(
            evalComplete("2", "./mill", "bar.task2", "--a"),
            Set("--arg-a-3  <str> arg a 3 docs", "--arg-b-4  <int> arg b 4 docs")
          )
        }
        test {
          assertGoldenLiteral(
            evalComplete("2", "./mill", "bar.task2", "--arg-b"),
            Set("--arg-b-4")
          )
        }

        test {
          assertGoldenLiteral(
            evalComplete("3", "./mill", "bar.task2", "--arg-a", "--"),
            Set()
          )
        }
        test {
          assertGoldenLiteral(
            evalComplete("3", "./mill", "bar.task2", "--arg-a", ""),
            Set("file2.txt", "file1.txt", "out")
          )
        }
        test {
          assertGoldenLiteral(
            evalComplete("5", "./mill", "bar.task2", "--arg-a", "", "--arg-b", ""),
            Set("file2.txt", "file1.txt", "out")
          )
        }
        test {
          assertGoldenLiteral(
            evalComplete("3", "./mill", "bar.task2", "--arg-a", "", "--arg-b", ""),
            Set("file2.txt", "file1.txt", "out")
          )
        }
      }
    }
  }
}
