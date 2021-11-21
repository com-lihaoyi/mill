import scala.scalajs.js.annotation.{JSExportTopLevel}

object MainB {

  @JSExportTopLevel(name = "startB", moduleID = "b")
  def entrypointB() = {
    println("Hello entrypoint B" + vmName)
  }

  println("Hello B " + vmName)

  def vmName = sys.props("java.vm.name")

  def main(): Unit = {
    println("Module init B")
  }
}
