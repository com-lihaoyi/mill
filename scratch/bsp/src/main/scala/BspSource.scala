package main.scala
import ch.epfl.scala.bsp4j._

object BspSource {
  val obj = CommonObject.strVal

  def main(args: Array[String]): Unit = {
    println(new BuildTargetIdentifier("path"))
  }
}
