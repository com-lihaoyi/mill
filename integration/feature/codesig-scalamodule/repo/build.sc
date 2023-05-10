import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = T{
    println("Generating Sources...")
    os.write(
      T.dest / "Foo.scala",
      """package foo
        |object Foo {
        |  def main(args: Array[String]): Unit = {
        |    println("Hello World")
        |  }
        |}""".stripMargin
    )
    Seq(PathRef(T.dest))
  }

  def compile = T {
    println("Compiling...")
    super.compile()
  }

  def run(args: Task[Args] = T.task(Args())) = T.command {
    println("Running..." + args().value.mkString(" "))
    super.run(args)()
  }
}