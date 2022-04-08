package mill.integration

import mill.util.ScriptTestSuite
import utest._

import scala.collection.mutable

class ScriptsInvalidationTests(fork: Boolean) extends ScriptTestSuite(fork) {
  override def workspaceSlug: String = "invalidation"
  override def workspacePath: os.Path = os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???)) / workspaceSlug
  override def scriptSourcePath: os.Path = os.pwd / "integration" / "local" / "resources" / workspaceSlug

  def runTask(task: String) = {
    val (successful, stdout) = evalStdout(task)
    assert(successful)
    stdout.map(_.trim)
  }

  val tests = Tests {
    test("should not invalidate tasks in different untouched sc files") {
      test("first run") {
        initWorkspace()

        val result = runTask("task")

        val expected = Seq("a", "d", "b", "c")

        assert(result == expected)
      }

      test("second run modifying script") {
        val oldContent = os.read(scriptSourcePath / buildPath)
        val newContent = s"""$oldContent
                            |def newTask = T { }
                            |""".stripMargin
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
        val newContent = s"""$oldContent
                            |def newTask = T { }
                            |""".stripMargin
        os.write.over(workspacePath / inputD, newContent)

        val result = runTask("task")
        val expected = Seq("d", "b")

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
        val newContent = s"""$oldContent
                            |def newTask = T { }
                            |""".stripMargin
        os.write.over(workspacePath / buildPath, newContent)

        val result = runTask("module.task")
        val expected = Seq("task")

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
        val newContent = s"""$oldContent
                            |def newTask = T { }
                            |""".stripMargin
        os.write.over(workspacePath / buildPath, newContent)

        val result = runTask("taskE")
        val expected = Seq("taskE")

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
