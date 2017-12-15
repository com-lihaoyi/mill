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

  def generateApplyer() = {
    def generate(n: Int) = {
      val uppercases = generateLetters(n)
      val lowercases = uppercases.map(Character.toLowerCase)
      val typeArgs   = uppercases.mkString(", ")
      val zipArgs    = lowercases.mkString(", ")
      val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: TT[$upper]" }.mkString(", ")

      val body   = s"mapCtx(zip($zipArgs)) { case (($zipArgs), z) => cb($zipArgs, z) }"
      val zipmap = s"def zipMap[$typeArgs, Res]($parameters)(cb: ($typeArgs, Ctx) => Z[Res]) = $body"
      val zip    = s"def zip[$typeArgs]($parameters): TT[($typeArgs)]"

      if (n < 22) List(zipmap, zip).mkString(System.lineSeparator) else zip
    }
    val output = List(
        "package mill.define",
        "import scala.language.higherKinds",
        "trait ApplyerGenerated[TT[_], Z[_], Ctx] {",
        "def mapCtx[A, B](a: TT[A])(f: (A, Ctx) => Z[B]): TT[B]",
        (2 to 22).map(generate).mkString(System.lineSeparator),
        "}").mkString(System.lineSeparator)

    write("ApplicativeGenerated.scala", output)
  }

  def generateTarget() = {
    def generate(n: Int) = {
      val uppercases = generateLetters(n)
      val lowercases = uppercases.map(Character.toLowerCase)
      val typeArgs   = uppercases.mkString(", ")
      val args       = lowercases.mkString(", ")
      val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: TT[$upper]" }.mkString(", ")
      val body       = uppercases.zipWithIndex.map { case (t, i) => s"args[$t]($i)" }.mkString(", ")

      s"def zip[$typeArgs]($parameters) = makeT[($typeArgs)](Seq($args), (args: Ctx) => ($body))"
    }

    val output = List(
      "package mill.define",
      "import scala.language.higherKinds",
      "import mill.eval.Result",
      "import mill.util.Ctx",
      "trait TargetGenerated {",
      "type TT[+X]",
      "def makeT[X](inputs: Seq[TT[_]], evaluate: Ctx => Result[X]): TT[X]",
      (3 to 22).map(generate).mkString(System.lineSeparator),
      "}").mkString(System.lineSeparator)
    write("TaskGenerated.scala", output)
  }

  def run() = {
    generateApplyer()
    generateTarget()
  }
}

Generator.run()
