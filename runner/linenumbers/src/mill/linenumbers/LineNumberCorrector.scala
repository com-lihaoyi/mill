package mill.linenumbers

import scala.tools.nsc.Global

object LineNumberCorrector {
  def apply(
      g: Global,
      lines: Seq[String],
      adjustedFile: String
  )(unit: g.CompilationUnit): g.Tree = {

    val userCodeStartMarker = "//MILL_USER_CODE_START_MARKER"

    import scala.reflect.internal.util._

    val markerLine = lines.indexWhere(_.startsWith(userCodeStartMarker))

    val topWrapperLen = lines.take(markerLine + 1).map(_.length).sum

    val trimmedSource = new BatchSourceFile(
      new scala.reflect.io.PlainFile(adjustedFile),
      g.currentSource.content.drop(topWrapperLen)
    )

    import scala.reflect.internal.util._
    object Transformer extends g.Transformer {
      override def transform(tree: g.Tree) = {
        val transformedTree = super.transform(tree)
        // The `start` and `end` values in transparent/range positions are left
        // untouched, because of some aggressive validation in scalac that checks
        // that trees are not overlapping, and shifting these values here
        // violates the invariant (which breaks Ammonite, potentially because
        // of multi-stage).
        // Moreover, we rely only on the "point" value (for error reporting).
        // The ticket https://github.com/scala/scala-dev/issues/390 tracks down
        // relaxing the aggressive validation.
        val newPos = tree.pos match {
          case s: TransparentPosition if s.start > topWrapperLen =>
            new TransparentPosition(
              trimmedSource,
              s.start - topWrapperLen,
              s.point - topWrapperLen,
              s.end - topWrapperLen
            )
          case s: RangePosition if s.start > topWrapperLen =>
            new RangePosition(
              trimmedSource,
              s.start - topWrapperLen,
              s.point - topWrapperLen,
              s.end - topWrapperLen
            )
          case s: OffsetPosition if s.start > topWrapperLen =>
            new OffsetPosition(trimmedSource, s.point - topWrapperLen)
          case s => s

        }
        transformedTree.pos = newPos

        transformedTree
      }
    }
    Transformer.transform(unit.body)
  }

}
