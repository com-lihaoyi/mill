package mill.define

import Segment.{Label, Cross}
import utest._

object SegmentsTests extends TestSuite {
  def check(pattern: Seq[Segment], input: Seq[Segment], success: Boolean) = {
    val res = Segments.checkPatternMatch(Segments(pattern), Segments(input))
    assert(res == success)
  }
  val tests = Tests {
    "matching" - {
      * - check(Seq(Label("a")), Seq(Label("b")), false)
      * - check(Seq(Label("a")), Seq(Label("a")), true)
      * - check(Seq(Label("__"), Label("a")), Seq(Label("a")), true)
      * - check(Seq(Label("__")), Seq(Label("a")), true)
      * - check(Seq(Label("_")), Seq(Label("a")), true)

      * - check(
        Seq(Label("a"), Cross(Seq("x")), Label("b")),
        Seq(Label("a"), Cross(Seq("x")), Label("b")),
        true
      )

      * - check(
        Seq(Label("a"), Cross(Seq("x")), Label("b")),
        Seq(Label("aa"), Cross(Seq("x")), Label("b")),
        false
      )

      * - check(
        Seq(Label("a"), Cross(Seq("x")), Label("b")),
        Seq(Label("a"), Cross(Seq("xx")), Label("b")),
        false
      )

      * - check(
        Seq(Label("a"), Cross(Seq("x")), Label("b")),
        Seq(Label("a"), Cross(Seq("x")), Label("bb")),
        false
      )

      * - check(
        Seq(Label("a"), Cross(Seq("x", "y")), Label("b")),
        Seq(Label("a"), Cross(Seq("x", "y")), Label("b")),
        true
      )
      * - check(
        Seq(Label("a"), Cross(Seq("_", "y")), Label("b")),
        Seq(Label("a"), Cross(Seq("x", "y")), Label("b")),
        true
      )
      * - check(
        Seq(Label("a"), Cross(Seq("x", "_")), Label("b")),
        Seq(Label("a"), Cross(Seq("x", "y")), Label("b")),
        true
      )
      * - check(
        Seq(Label("a"), Label("_"), Label("b")),
        Seq(Label("a"), Cross(Seq("x", "y")), Label("b")),
        true
      )
      * - check(
        Seq(Label("a"), Label("__"), Label("b")),
        Seq(Label("a"), Cross(Seq("x", "y")), Label("b")),
        true
      )
    }
  }
}
