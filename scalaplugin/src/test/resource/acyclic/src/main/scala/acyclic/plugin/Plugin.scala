package acyclic.plugin
import acyclic.file
import tools.nsc.Global
import scala.collection.SortedSet

class RuntimePlugin(global: Global) extends TestPlugin(global)
class TestPlugin(val global: Global,
                 cycleReporter: Seq[(Value, SortedSet[Int])] => Unit = _ => ())
  extends tools.nsc.plugins.Plugin {

  val name = "acyclic"

  var force = false
  // Yeah processOptions is deprecated but keep using it anyway for 2.10.x compatibility
  override def processOptions(options: List[String], error: String => Unit): Unit = {
    if (options.contains("force")) {
      force = true
    }
  }
  val description = "Allows the developer to prohibit inter-file dependencies"


  val components = List[tools.nsc.plugins.PluginComponent](
    new PluginPhase(this.global, cycleReporter, force)
  )
}
