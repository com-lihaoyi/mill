import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = T{
    println("Foo generating sources...")
    os.write(
      T.dest / "Foo.scala",
      """package foo
        |object Foo {
        |  final val fooMsg = "Hello World"
        |  def x: Seq[String] = Nil // make sure the compiled code changes between Scala 2.12/2.13
        |  def main(args: Array[String]): Unit = {
        |    println("Foo " + fooMsg)
        |  }
        |}""".stripMargin
    )
    Seq(PathRef(T.dest))
  }

  def compile = T {
    println("Foo compiling...")
    super.compile()
  }

  def run(args: Task[Args] = T.task(Args())) = T.command {
    println("Foo running..." + args().value.mkString(" "))
    super.run(args)()
  }

  def assembly = T {
    println("Foo assembly...")
    super.assembly()
  }
}

object bar extends ScalaModule {
  def moduleDeps = Seq(foo)
  def scalaVersion = "2.13.8"

  def sources = T {
    println("Bar generating sources...")
    os.write(
      T.dest / "Bar.scala",
      """package bar
        |object Bar {
        |  def main(args: Array[String]): Unit = {
        |    println("Bar " + foo.Foo.fooMsg)
        |  }
        |}""".stripMargin
    )
    Seq(PathRef(T.dest))
  }

  def compile = T {
    println("Bar compiling...")
    super.compile()
  }

  def assembly = T {
    println("Bar assembly...")
    super.assembly()
  }
}

object qux extends ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = T {
    println("Qux generating sources...")
    os.write(
      T.dest / "Qux.scala",
      """package qux
        |object Qux {
        |  def main(args: Array[String]): Unit = {
        |    println("Qux Hello World")
        |  }
        |}""".stripMargin
    )
    Seq(PathRef(T.dest))
  }

  def compile = T {
    println("Qux compiling...")
    super.compile()
  }

  def assembly = T {
    println("Qux assembly...")
    super.assembly()
  }
}