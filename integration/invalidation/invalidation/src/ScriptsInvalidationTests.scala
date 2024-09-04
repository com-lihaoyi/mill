package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object ScriptsInvalidationTests extends IntegrationTestSuite {

  def runTask(task: String): Set[String] = {
    val res = eval(task)
    assert(res.isSuccess)
    res.out.linesIterator.map(_.trim).toSet
  }

  val tests: Tests = Tests {
    test("should not invalidate tasks in different untouched sc files") {
      test("first run") {
        initWorkspace()

        val result = runTask("task")

        val expected = Set("a", "d", "b", "c")

        assert(result == expected)
      }

      test("second run modifying script") {
        modifyFile(
          workspacePath / "build.mill",
          _.replace("""println("task")""", """System.out.println("task2")""")
        )

        val stdout = runTask("task")

        assert(stdout.isEmpty)
      }
    }
    test("should invalidate tasks if leaf file is changed") {
      test("first run") {
        initWorkspace()

        val result = runTask("task")
        val expected = Set("a", "d", "b", "c")

        assert(result == expected)
      }

      test("second run modifying script") {
        modifyFile(
          workspacePath / "b" / "inputD.mill",
          _.replace("""println("d")""", """System.out.println("d2")""")
        )

        val result = runTask("task")
        val expected = Set("d2", "b")

        assert(result == expected)
      }
    }
    test("should handle submodules in scripts") {
      test("first run") {
        initWorkspace()

        val result = runTask("module.task")
        val expected = Set("a", "d", "b", "c", "task")

        assert(result == expected)
      }

      test("second run modifying script") {
        modifyFile(
          workspacePath / "build.mill",
          _.replace("""println("task")""", """System.out.println("task2")""")
        )

        val result = runTask("module.task")
        val expected = Set("task2")

        assert(result == expected)
      }
    }
    test("should handle ammonite ^ imports") {
      test("first run") {
        initWorkspace()

        val result = runTask("taskE")
        val expected = Set("a", "e", "taskE")

        assert(result == expected)
      }

      test("second run modifying script") {
        modifyFile(
          workspacePath / "build.mill",
          _.replace("""println("taskE")""", """System.out.println("taskE2")""")
        )

        val result = runTask("taskE")
        val expected = Set("taskE2")

        assert(result == expected)
      }
    }
    test("should handle ammonite paths with symbols") {
      initWorkspace()

      val result = runTask("taskSymbols")
      val expected = Set("taskSymbols")

      assert(result == expected)
    }
    test("should handle ammonite files with symbols") {
      initWorkspace()

      val result = runTask("taskSymbolsInFile")
      val expected = Set("taskSymbolsInFile")

      assert(result == expected)
    }
  }
}
