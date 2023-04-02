package mill.integration

import os.Path
import utest._

import scala.collection.mutable

object ScriptsInvalidationForeignTests extends IntegrationTestSuite {

  override def buildPath = os.sub / "foreignA" / "build.sc"

  def runTask(task: String) = {
    val res = evalStdout(task)
    assert(res.isSuccess)
    res.out.linesIterator.map(_.trim).toVector
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
