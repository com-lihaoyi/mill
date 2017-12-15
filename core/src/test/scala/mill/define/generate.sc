
object Generator {
  private def generateLetters(n: Int) = {
    val base = 'A'.toInt
    (0 until n).map(i => (i + base).toChar)
  }

  private def write(filename: String, s: String) = {
    import java.io.{BufferedWriter, File, FileWriter}
    val file = new File(filename)
    val w = new BufferedWriter(new FileWriter(file))
    w.write(s)
    w.close()
  }

  def generateApplicativeTest() = {
    def generate(n: Int): String = {
        val uppercases = generateLetters(n)
        val lowercases = uppercases.map(Character.toLowerCase)
        val typeArgs   = uppercases.mkString(", ")
        val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: Option[$upper]" }.mkString(", ")
        val result = lowercases.mkString(", ")
        val forArgs = lowercases.map(i => s"$i <- $i").mkString("; ")
        s"def zip[$typeArgs]($parameters) = { for ($forArgs) yield ($result) }"
    }

    val output = List(
        "package mill.define",
        "trait OptGenerated {",
        (2 to 22).map(generate).mkString(System.lineSeparator),
        "}"
    ).mkString(System.lineSeparator)

    write("ApplicativeTestsGenerated.scala", output)
  }

  def run() = {
      generateApplicativeTest()
  }
}

Generator.run()
