package mill.integration

import utest._

object ScriptsInvalidationTests extends IntegrationTestSuite {

  def runTask(task: String): Vector[String] = {
    val res = evalStdout(task)
    assert(res.isSuccess)
    res.out.linesIterator.map(_.trim).toVector
  }

  val tests: Tests = Tests {
    test("should not invalidate tasks in different untouched sc files") {
      test("first run") {
        initWorkspace()

        val result = runTask("task")

        val expected = Seq("a", "d", "b", "c")

        assert(result == expected)
      }

      test("second run modifying script") {
        val oldContent = os.read(scriptSourcePath / buildPath)
        val newContent =
          oldContent.replace("""println("task")""", """System.out.println("task2")""")

        os.write.over(workspacePath / buildPath, newContent)

        val stdout = runTask("task")

        assert(stdout.isEmpty)
      }
    }
    test("should invalidate tasks if leaf file is changed") {
      test("first run") {
        initWorkspace()

        val result = runTask("task")
        val expected = Seq("a", "d", "b", "c")

        assert(result == expected)
      }

      test("second run modifying script") {
        val inputD = os.sub / "b" / "inputD.sc"
        val oldContent = os.read(scriptSourcePath / inputD)
        val newContent = oldContent.replace("""println("d")""", """System.out.println("d2")""")
        os.write.over(workspacePath / inputD, newContent)

        val result = runTask("task")
        val expected = Seq("d2", "b")

        assert(result == expected)
      }
    }
    test("should handle submodules in scripts") {
      test("first run") {
        initWorkspace()

        val result = runTask("module.task")
        val expected = Seq("a", "d", "b", "c", "task")

        assert(result == expected)
      }

      test("second run modifying script") {
        val oldContent = os.read(scriptSourcePath / buildPath)
        val newContent =
          oldContent.replace("""println("task")""", """System.out.println("task2")""")
        os.write.over(workspacePath / buildPath, newContent)

        val result = runTask("module.task")
        val expected = Seq("task2")

        assert(result == expected)
      }
    }
    test("should handle ammonite ^ imports") {
      test("first run") {
        initWorkspace()

        val result = runTask("taskE")
        val expected = Seq("a", "e", "taskE")

        assert(result == expected)
      }

      test("second run modifying script") {
        val oldContent = os.read(scriptSourcePath / buildPath)
        val newContent =
          oldContent.replace("""println("taskE")""", """System.out.println("taskE2")""")
        os.write.over(workspacePath / buildPath, newContent)

        val result = runTask("taskE")
        val expected = Seq("taskE2")

        assert(result == expected)
      }
    }
    test("should handle ammonite paths with symbols") {
      initWorkspace()

      val result = runTask("taskSymbols")
      val expected = Seq("taskSymbols")

      assert(result == expected)
    }
    test("should handle ammonite files with symbols") {
      initWorkspace()

      val result = runTask("taskSymbolsInFile")
      val expected = Seq("taskSymbolsInFile")

      assert(result == expected)
    }
  }
}
