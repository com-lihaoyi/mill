import sandbox.meta._

object Demo {
  def main(args: Array[String]): Unit = {
    // This is a macro call
    PrintMac {
      val x = 1
      val y = 2
      val z = x + y
      println(s"x + y = $z")
    }

    PrintMac.toErr {
      println("Hello, world!!!")
    }
  }
}

