package app

import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}

@JSExportTopLevel("Box")
class Box {
  @JSExport
  def answer: Int = 42
}

object App {
  def main(args: Array[String]): Unit = ()
}
