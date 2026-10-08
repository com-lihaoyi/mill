package thing

object Thing {
  val toReformat =
    """{ "a":2,          "b": true }
      |
      |""".stripMargin
  def reformatted = ujson.reformat(toReformat, indent = 2)
  def main(args: Array[String]): Unit = {
    println(reformatted)
  }
}
