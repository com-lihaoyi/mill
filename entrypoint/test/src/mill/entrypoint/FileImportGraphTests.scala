package mill.entrypoint

import mill.define.ScriptNode
import utest._

object FileImportGraphTests extends TestSuite {
  val tests = Tests {
    test("linksToScriptNodeGraph") {
      test("path") {
        val input = Map(
          os.pwd / "A" -> Seq(os.pwd / "B"),
          os.pwd / "B" -> Seq(os.pwd / "C"),
          os.pwd / "C" -> Seq(os.pwd / "D"),
          os.pwd / "D" -> Seq(os.pwd / "E"),
          os.pwd / "E" -> Seq(os.pwd / "F"),
          os.pwd / "F" -> Seq()
        )

        val result = FileImportGraph.linksToScriptNodeGraph(os.pwd, input)

        val f = ScriptNode("F", Seq(), os.pwd)
        val e = ScriptNode("E", Seq(f), os.pwd)
        val d = ScriptNode("D", Seq(e), os.pwd)
        val c = ScriptNode("C", Seq(d), os.pwd)
        val b = ScriptNode("B", Seq(c), os.pwd)
        val a = ScriptNode("A", Seq(b), os.pwd)
        val expected = Set(a, b, c, d, e, f)

        assert(result.toSet == expected)
      }
      test("graph") {
        val input = Map(
          os.pwd / "A" -> Seq(os.pwd / "B", os.pwd / "C"),
          os.pwd / "B" -> Seq(os.pwd / "D"),
          os.pwd / "C" -> Seq(os.pwd / "D"),
          os.pwd / "D" -> Seq()
        )

        val result = FileImportGraph.linksToScriptNodeGraph(os.pwd, input)

        val d = ScriptNode("D", Seq(), os.pwd)
        val c = ScriptNode("C", Seq(d), os.pwd)
        val b = ScriptNode("B", Seq(d), os.pwd)
        val a = ScriptNode("A", Seq(b, c), os.pwd)
        val expected = Set(a, b, c, d)

        assert(result.toSet == expected)
      }
    }
  }
}
