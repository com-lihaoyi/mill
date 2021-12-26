import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExportTopLevel}

object MainA extends App {

  @JSExportTopLevel(name = "startA", moduleID = "main")
  def entrypointA() = {
    println("Hello entrypoint A" + vmName)
  }

  println("Hello A " + vmName)

  def vmName = sys.props("java.vm.name")

  def main(): Unit = {
    println("Module init A")
  }
}
