package mill.linenumbers

import scala.tools.nsc._
import scala.tools.nsc.plugins.{Plugin, PluginComponent}

/**
 * Used to capture the names in scope after every execution, reporting them
 * to the `output` function. Needs to be a compiler plugin so we can hook in
 * immediately after the `typer`
 */
class LineNumberPlugin(val global: Global) extends Plugin {
  override def init(options: List[String], error: String => Unit): Boolean = true
  val name: String = "mill-linenumber-plugin"
  val description = "Adjusts line numbers in the user-provided script to compensate for wrapping"
  val components: List[PluginComponent] = List(
    new PluginComponent {
      val global = LineNumberPlugin.this.global
      val runsAfter = List("parser")
      val phaseName = "FixLineNumbers"
      def newPhase(prev: Phase): Phase = new global.GlobalPhase(prev) {
        def name = phaseName
        def apply(unit: global.CompilationUnit): Unit = {
          LineNumberPlugin.apply(global)(unit)
        }
      }
    }
  )
}

object LineNumberPlugin {
  def apply(g: Global)(unit: g.CompilationUnit) = {

    object LineNumberCorrector extends g.Transformer {
      import scala.reflect.internal.util._

      val str = new String(g.currentSource.content)
      val userCodeStartMarker = "//MILL_USER_CODE_START_MARKER"
      val lines = str.linesWithSeparators.toVector

      val adjustedFile = lines
        .collectFirst { case s"//MILL_ORIGINAL_FILE_PATH=$rest" => rest.trim }
        .get

      val markerLine = lines.indexWhere(_.startsWith(userCodeStartMarker))

      val topWrapperLen = lines.take(markerLine + 1).map(_.length).sum

      val trimmedSource = new BatchSourceFile(
        new scala.reflect.io.PlainFile(adjustedFile),
        g.currentSource.content.drop(topWrapperLen)
      )

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

      def apply(unit: g.CompilationUnit) = transform(unit.body)
    }

    if (g.currentSource.file.hasExtension("sc")) {
      unit.body = LineNumberCorrector(unit)
    }
  }
}
