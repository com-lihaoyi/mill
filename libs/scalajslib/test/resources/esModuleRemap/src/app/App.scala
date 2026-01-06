package app

import scala.scalajs.js
import scala.scalajs.js.annotation.*

object App {
  def main(args: Array[String]): Unit = {
    println(linspace(-10.0, 10.0, 10))
  }
}

@js.native
@JSImport("@stdlib/linspace", JSImport.Default)
object linspace extends js.Object {
  def apply(start: Double, stop: Double, num: Int): Any = js.native
}
