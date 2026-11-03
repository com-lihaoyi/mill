object Repro {
  def main(args: Array[String]): Unit = {

    // A complete colour code (so stripAnsi takes the fansi path), then a lone CSI
    println("\u001b[31mcoloured\u001b[0m then a lone CSI \u009b")

    val line = "x" * 999
    for (_ <- 1 to 800) println(line) // about 80 KB

    println("after")
  }
}