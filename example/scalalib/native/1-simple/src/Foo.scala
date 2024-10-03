package foo

object Main {
  val colored = Console.GREEN + "Hello World Ansi!" + Console.RESET
  def main(args: Array[String]): Unit =
    println(colored)
}
