package mill.integration

import mill.util.ScriptTestSuite
import utest._

import scala.collection.mutable

class ScriptsInvalidationForeignTests(fork: Boolean) extends ScriptTestSuite(fork) {
  def workspaceSlug: String = "invalidation-foreign"
  def scriptSourcePath: os.Path = os.pwd / "integration" / "test" / "resources" / workspaceSlug
  override def buildPath = os.sub / "foreignA" / "build.sc"
  override def wd = super.wd / buildPath / os.up

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
  }
}
