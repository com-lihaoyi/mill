package mill.linenumbers

import mill.main.client.CodeGenConstants.buildFileExtensions
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
  def apply(g: Global)(unit: g.CompilationUnit): Unit = {
    if (buildFileExtensions.exists(ex => g.currentSource.file.name.endsWith(s".$ex"))) {

      val str = new String(g.currentSource.content)
      val lines = str.linesWithSeparators.toVector
      val adjustedFile = lines
        .collectFirst { case s"//MILL_ORIGINAL_FILE_PATH=$rest" => rest.trim }
        .getOrElse(sys.error(g.currentSource.path))

      unit.body = LineNumberCorrector(g, lines, adjustedFile)(unit)
    }
  }
}
