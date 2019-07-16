package main.scala

import org.apache.commons.io.FileUtils
import java.io.File
//import com.williamfiset.fastjavaio.InputReader

object BenchmarkSource {
  //val reader = new InputReader()

  def main(args: Array[String]): Unit = {
    val file = FileUtils.getFile("/home/alexandra/test_build1/build.sc")
    println(file)
  }
}
