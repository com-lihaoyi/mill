package mill.entrypoint

import mill.define.ScriptNode
import utest._

object GraphUtilsTests extends TestSuite {
  val tests = Tests {
    test("linksToScriptNodeGraph") {
      test("path") {
        val input = Map(
          "A" -> Seq("B"),
          "B" -> Seq("C"),
          "C" -> Seq("D"),
          "D" -> Seq("E"),
          "E" -> Seq("F"),
          "F" -> Seq()
        )

        val result = GraphUtils.linksToScriptNodeGraph(input)

        val f = ScriptNode("F", Seq())
        val e = ScriptNode("E", Seq(f))
        val d = ScriptNode("D", Seq(e))
        val c = ScriptNode("C", Seq(d))
        val b = ScriptNode("B", Seq(c))
        val a = ScriptNode("A", Seq(b))
        val expected = Set(a, b, c, d, e, f)

        assert(result.toSet == expected)
      }
      test("graph") {
        val input = Map(
          "A" -> Seq("B", "C"),
          "B" -> Seq("D"),
          "C" -> Seq("D"),
          "D" -> Seq()
        )

        val result = GraphUtils.linksToScriptNodeGraph(input)

        val d = ScriptNode("D", Seq())
        val c = ScriptNode("C", Seq(d))
        val b = ScriptNode("B", Seq(d))
        val a = ScriptNode("A", Seq(b, c))
        val expected = Set(a, b, c, d)

        assert(result.toSet == expected)
      }
    }
  }
}
