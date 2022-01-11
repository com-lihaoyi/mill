package mill.integration

import mill.util.ScriptTestSuite
import utest._

import scala.collection.mutable

class ScriptsInvalidationTests(fork: Boolean) extends ScriptTestSuite(fork) {
  def workspaceSlug: String = "invalidation"
  def scriptSourcePath: os.Path = os.pwd / "integration" / "test" / "resources" / workspaceSlug

  val output = mutable.Buffer[String]()
  val stdout = os.ProcessOutput((bytes, count) => output += new String(bytes, 0, count))
  def runTask() = assert(eval(stdout, "task"))

  override def utestBeforeEach(path: Seq[String]): Unit = {
    output.clear()
  }

  val tests = Tests {
    test("should not invalidate tasks in different untouched sc files") {
      test("first run") {
        initWorkspace()
        runTask()

        val result = output.map(_.trim).toList
        val expected = List("a", "d", "b", "c")

        assert(result == expected)
      }

      test("second run modifying script") {
        val oldContent = os.read(scriptSourcePath / buildPath)
        val newContent = s"""$oldContent
                            |def newTask = T { }
                            |""".stripMargin
        os.write.over(workspacePath / buildPath, newContent)

        runTask()

        assert(output.isEmpty)
      }
    }
    test("should invalidate tasks if leaf file is changed") {
      test("first run") {
        initWorkspace()
        runTask()

        val result = output.map(_.trim).toList
        val expected = List("a", "d", "b", "c")

        assert(result == expected)
      }

      test("second run modifying script") {
        val inputD = os.sub / "b" / "inputD.sc"
        val oldContent = os.read(scriptSourcePath / inputD)
        val newContent = s"""$oldContent
                            |def newTask = T { }
                            |""".stripMargin
        os.write.over(workspacePath / inputD, newContent)

        runTask()

        val result = output.map(_.trim).toList
        val expected = List("d", "b")

        assert(result == expected)
      }
    }
  }
}
