package mill.integration

import mill.util.ScriptTestSuite
import os.Path
import utest._

import scala.collection.mutable

class ScriptsInvalidationForeignTests(fork: Boolean) extends ScriptTestSuite(fork) {
  override def workspaceSlug: String = "invalidation-foreign"
  override def workspacePath: os.Path = os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???)) / getClass().getName()
  override def scriptSourcePath: os.Path = os.pwd / "integration" / "local" / "resources" / workspaceSlug

  override def buildPath = os.sub / "foreignA" / "build.sc"

  def runTask(task: String) = {
    val (successful, stdout) = evalStdout(task)
    assert(successful)
    stdout.map(_.trim)
  }

  val tests = Tests {
    test("should handle foreign modules") {
      test("first run") {
        initWorkspace()

        val result = runTask("taskA")

        val expected = Seq("b", "a")

        assert(result == expected)
      }

      test("second run modifying script") {
        val oldContent = os.read(scriptSourcePath / buildPath)
        val newContent = s"""$oldContent
                            |def newTask = T { }
                            |""".stripMargin
        os.write.over(workspacePath / buildPath, newContent)

        val result = runTask("taskA")

        val expected = Seq("a")

        assert(result == expected)
      }
    }
    test("should handle imports in higher level than top level") {
      test("first run") {
        initWorkspace()

        val result = runTask("taskD")

        val expected = Seq("c", "d")

        assert(result == expected)
      }

      test("second run modifying script") {
        val oldContent = os.read(scriptSourcePath / buildPath)
        val newContent = s"""$oldContent
                            |def newTask = T { }
                            |""".stripMargin
        os.write.over(workspacePath / buildPath, newContent)

        val result = runTask("taskD")

        val expected = Seq("d")

        assert(result == expected)
      }
    }
  }
}
