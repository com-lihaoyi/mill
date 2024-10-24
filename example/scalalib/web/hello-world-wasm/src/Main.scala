package hello

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

object Main {
  @JSExportTopLevel("main")
  def main(): Unit = {
    println("Hello, WebAssembly!")
  }
}