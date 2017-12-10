import java.io.{BufferedWriter, File, FileWriter}

object Generator {
  private def generateLetters(n: Int) = {
    val base = 'A'.toInt
    (0 until n).map(i => (i + base).toChar)
  }

  def generateZipMap(): String = {
    def generate(n: Int) = {
      val uppercases = generateLetters(n)
      val lowercases = uppercases.map(Character.toLowerCase)
      val typeArgs   = uppercases.mkString(", ")
      val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"${lower}: TT[$upper]" }.mkString(", ")
      val zipArgs = lowercases.mkString(", ")
      val cbArgs  = zipArgs + ", z"
      val body = s"mapCtx(zip($zipArgs)) { case (($zipArgs), z) => cb($cbArgs) }"
      List(
        s"def zipMap[$typeArgs, Res]($parameters)(cb: ($typeArgs, Ctx) => Z[Res]) = $body",
        s"def zip[$typeArgs]($parameters): TT[($typeArgs)]"
      ).mkString(System.lineSeparator)
    }

    (2 until 22).map(generate).mkString(System.lineSeparator)
  }

  def generateApplyer() = {
    val ApplyerGenerated = List(
        "package mill.define",
        "import scala.language.higherKinds",
        "trait ApplyerGenerated[TT[_], Z[_], Ctx] {",
        "def mapCtx[A, B](a: TT[A])(f: (A, Ctx) => Z[B]): TT[B]",
        generateZipMap(),
        "}").mkString(System.lineSeparator)

    val file = new File("ApplicativeGenerated.scala")
    val w = new BufferedWriter(new FileWriter(file))
    w.write(ApplyerGenerated)
    w.close()
  }

  def run() = {
    generateApplyer()
  }
}

Generator.run()
