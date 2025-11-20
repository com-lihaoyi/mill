package mill.tabcomplete

import mill.Task
import mill.api.{Cross, Discover, Module}
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.annotation.unused
import scala.collection.immutable.HashSet

object TabCompleteTests extends TestSuite {
  object mainModule extends TestRootModule {
    lazy val millDiscover = Discover[this.type]
    def task1(@unused argA: String = "", @unused argB2: Int = 0) = Task.Command { 123 }
    object foo extends Module
    object bar extends Module {
      def task2(
          @mainargs.arg(doc = "arg a 3 docs") @unused argA3: String,
          @mainargs.arg(doc = "arg b 4 docs") @unused argB4: Int
      ) = Task.Command { 456 }
      def taskPositional(
          @mainargs.arg(positional = true) @unused argA3: String,
          @mainargs.arg(positional = true) @unused argB4: Int
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
        os.write(tester.evaluator.workspace / "folder/file3.txt", "", createFolders = true)
        tester.evaluator.evaluate(Seq("mill.tabcomplete.TabCompleteModule/complete") ++ s).get
      }
      outStream.toString.linesIterator.toSet
    }

    test("tasks") {
      test("empty-bash") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", ""),
          HashSet("qux", "file2.txt", "out", "folder", "bar", "file1.txt", "task1", "foo")
        )
      }
      test("empty-zsh") {
        assertGoldenLiteral(
          evalComplete("1", "./mill"),
          HashSet("qux", "file2.txt", "out", "folder", "bar", "file1.txt", "task1", "foo")
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
          Set("file2.txt", "file1.txt", "folder")
        )
      }
      test("secondNonTaskEmpty") {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "bar.task2", ""),
          Set("file2.txt", "file1.txt", "out", "folder")
        )
      }
      test("nestedNonTask") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "folder/"),
          Set("folder/file3.txt")
        )
      }
      test("nestedNonTask2") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "folder/f"),
          Set("folder/file3.txt")
        )
      }
      test("nestedNonTask2") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "folder/doesntExist"),
          Set()
        )
      }

      test("module") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "fo"),
          Set("foo", "folder")
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
            "-k  Continue build, even after build failures.",
            "-D  <k=v> Define (or overwrite) a system property.",
            "-d  Show debug output on STDOUT",
            "-i  Run Mill in interactive mode, suitable for opening REPLs and taking user input. Identical to --no-daemon. Must be the first argument.",
            "-v  Show mill version information and exit.",
            "-j  <str> The number of parallel threads. It can be an integer e.g. `5` meaning 5 threads, an expression e.g. `0.5C` meaning half as many threads as available cores, or `C-2` meaning 2 threads less than the number of cores. `1` disables parallelism and `0` (the default) uses 1 thread per core."
          )
        )
      }
      test("emptyAfterFlag") {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "-v"),
          HashSet("qux", "file2.txt", "out", "folder", "bar", "file1.txt", "task1", "foo")
        )

      }
      test("filterAfterFlag") {
        assertGoldenLiteral(
          evalComplete("2", "./mill", "-v", "f"),
          Set("foo", "file2.txt", "file1.txt", "folder")
        )
      }
      test("filterAfterFlagAfterTask") {
        assertGoldenLiteral(
          evalComplete("3", "./mill", "-v", "task1", "f"),
          Set("file2.txt", "file1.txt", "folder")
        )
      }

      test("long") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "--"),
          HashSet(
            "--debug                   Show debug output on STDOUT",
            "--bell                    Ring the bell once if the run completes successfully, twice if it fails.",
            "--interactive             Run Mill in interactive mode, suitable for opening REPLs and taking user input. Identical to --no-daemon. Must be the first argument.",
            "--no-build-lock           Evaluate tasks / commands without acquiring an exclusive lock on the Mill output directory",
            "--tab-complete            Runs Mill in tab-completion mode",
            "--import                  <str> Additional ivy dependencies to load into mill, e.g. plugins.",
            "--no-daemon               Run without a long-lived background daemon. Must be the first argument.",
            "--allow-positional        Allows command args to be passed positionally without `--arg` by default",
            "--watch                   Watch and re-run the given tasks when when their inputs change.",
            "--no-wait-for-build-lock  Do not wait for an exclusive lock on the Mill output directory to evaluate tasks / commands.",
            "--bsp                     Enable BSP server mode. Typically used by a BSP client when starting the Mill BSP server.",
            "--offline                 Try to work offline. This tells modules that support it to work offline and avoid any access to the internet. This is on a best effort basis. There are currently no guarantees that modules don't attempt to fetch remote sources.",
            "--keep-going              Continue build, even after build failures.",
            "--define                  <k=v> Define (or overwrite) a system property.",
            "--no-filesystem-checker   Globally disables the checks that prevent you from reading and writing to disallowed files or folders during evaluation. Useful as an escape hatch in case you desperately need to do something unusual and you are willing to take the risk",
            "--notify-watch            <bool> Use filesystem based file watching instead of polling based one (defaults to true).",
            "--bsp-watch               <bool> Automatically reload the build when its sources change when running the BSP server (defaults to true).",
            "--repl                    Open a Scala REPL with the classpath of the meta-level 1 build module (mill-build/). Implies options `--meta-level 1` and `--no-server`.",
            "--bsp-install             Create mill-bsp.json with Mill details under .bsp/",
            "--meta-level              <int> Select a meta-level to run the given tasks. Level 0 is the main project in `build.mill`, level 1 the first meta-build in `mill-build/build.mill`, etc.",
            "--help                    Print this help message and exit.",
            "--jobs                    <str> The number of parallel threads. It can be an integer e.g. `5` meaning 5 threads, an expression e.g. `0.5C` meaning half as many threads as available cores, or `C-2` meaning 2 threads less than the number of cores. `1` disables parallelism and `0` (the default) uses 1 thread per core.",
            "--ticker                  <bool> Enable or disable the ticker log, which provides information on running tasks and where each log line came from",
            "--color                   <bool> Toggle colored output; by default enabled only if the console is interactive and NO_COLOR environment variable is not set",
            "--version                 Show mill version information and exit.",
            "--task                    <str> The name or a query of the tasks(s) you want to build.",
            "--help-advanced           Print a internal or advanced command flags not intended for common usage",
            "--jshell                  Open a JShell REPL with the classpath of the meta-level 1 build module (mill-build/). This is useful for interactively testing and debugging your build logic. Implies options `--meta-level 1` and `--no-server`."
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
          Set(
            "--jobs",
            "--jobs: <str> The number of parallel threads. It can be an integer e.g. `5` meaning 5 threads, an expression e.g. `0.5C` meaning half as many threads as available cores, or `C-2` meaning 2 threads less than the number of cores. `1` disables parallelism and `0` (the default) uses 1 thread per core."
          )
        )
      }
      test("longFlagCompleteEarlier") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "--jobs", "1", "task1"),
          Set(
            "--jobs",
            "--jobs: <str> The number of parallel threads. It can be an integer e.g. `5` meaning 5 threads, an expression e.g. `0.5C` meaning half as many threads as available cores, or `C-2` meaning 2 threads less than the number of cores. `1` disables parallelism and `0` (the default) uses 1 thread per core."
          )
        )
      }
      test("longFlagIncomplete") {
        assertGoldenLiteral(
          evalComplete("1", "./mill", "--jobs"),
          Set(
            "--jobs",
            "--jobs: <str> The number of parallel threads. It can be an integer e.g. `5` meaning 5 threads, an expression e.g. `0.5C` meaning half as many threads as available cores, or `C-2` meaning 2 threads less than the number of cores. `1` disables parallelism and `0` (the default) uses 1 thread per core."
          )
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
            Set("--arg-b-2", "--arg-b-2: <int> ")
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
            Set("file2.txt", "file1.txt", "out", "folder")
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
            Set("--arg-b-4", "--arg-b-4: <int> arg b 4 docs")
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
            Set("file2.txt", "file1.txt", "out", "folder")
          )
        }
        test {
          assertGoldenLiteral(
            evalComplete("5", "./mill", "bar.task2", "--arg-a", "", "--arg-b", ""),
            Set("file2.txt", "file1.txt", "out", "folder")
          )
        }
        test {
          assertGoldenLiteral(
            evalComplete("3", "./mill", "bar.task2", "--arg-a", "", "--arg-b", ""),
            Set("file2.txt", "file1.txt", "out", "folder")
          )
        }
      }
    }
  }
}
