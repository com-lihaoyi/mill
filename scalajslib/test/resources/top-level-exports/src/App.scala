package my.app

import scala.collection.mutable
import scala.scalajs.js.annotation._

object AppA {
  @JSExportTopLevel(name = "start", moduleID = "a")
  def a(): Unit = println("hello from a")
}

object AppB {
  private val x = mutable.Set.empty[String]

  @JSExportTopLevel(name = "start", moduleID = "b")
  def b(): Unit = {
    println("hello from b")
    println(x)
  }

  def main(): Unit = x.add("something")
}
object App {
  def main(args: Array[String]): Unit = {
    println("Hello")
  }
}
