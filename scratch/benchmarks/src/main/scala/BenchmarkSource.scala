package main.scala

import org.apache.commons.io.FileUtils

import java.io.File

object BenchmarkSource {
  //val reader = new InputReader()


  def main(args: Array[String]): Unit = {
    val unusedValue = 3
    val file = FileUtils.getFile("/home/alexandra/test_build1/build.sc")
    println(file)
  }
}
