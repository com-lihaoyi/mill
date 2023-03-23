package mill.entrypoint

import mill.define.ScriptNode
import utest._

object GraphUtilsTests extends TestSuite {
  val tests = Tests {
    test("linksToScriptNodeGraph") {
      test("path") {
        val input = Map(
          "A" -> (os.pwd, Seq("B")),
          "B" -> (os.pwd, Seq("C")),
          "C" -> (os.pwd, Seq("D")),
          "D" -> (os.pwd, Seq("E")),
          "E" -> (os.pwd, Seq("F")),
          "F" -> (os.pwd, Seq())
        )

        val result = GraphUtils.linksToScriptNodeGraph(input)

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
          "A" -> (os.pwd, Seq("B", "C")),
          "B" -> (os.pwd, Seq("D")),
          "C" -> (os.pwd, Seq("D")),
          "D" -> (os.pwd, Seq())
        )

        val result = GraphUtils.linksToScriptNodeGraph(input)

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
