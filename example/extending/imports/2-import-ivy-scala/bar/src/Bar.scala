package bar

object Bar {
  val value = os.read(os.resource / "snippet.txt")
  def main(args: Array[String]): Unit = {
    println("generated snippet.txt resource: " + value)
  }
}
