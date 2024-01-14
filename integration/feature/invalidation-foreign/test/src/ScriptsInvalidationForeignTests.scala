package mill.integration

import utest._
import os.SubPath

object ScriptsInvalidationForeignTests extends IntegrationTestSuite {

  override def buildPath: SubPath = os.sub / "foreignA" / "build.sc"

  def runTask(task: String): Vector[String] = {
    val res = evalStdout(task)
    assert(res.isSuccess)
    res.out.linesIterator.map(_.trim).toVector
  }

  val tests: Tests = Tests {
    test("should handle foreign modules") {
      test("first run") {
        initWorkspace()

        val result = runTask("taskA")

        val expected = Seq("b", "a")

        assert(result == expected)
      }

      test("second run modifying script") {
        val oldContent = os.read(scriptSourcePath / buildPath)
        val newContent =
          oldContent.replace("""println("a")""", """println("a2")""")
        os.write.over(workspacePath / buildPath, newContent)

        val result = runTask("taskA")

        val expected = Seq("a2")

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
        val newContent =
          oldContent.replace("""println("d")""", """println("d2")""")
        os.write.over(workspacePath / buildPath, newContent)

        val result = runTask("taskD")

        val expected = Seq("d2")

        assert(result == expected)
      }
    }
  }
}
