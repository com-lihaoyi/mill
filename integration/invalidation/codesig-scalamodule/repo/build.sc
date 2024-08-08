import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = T{
    println("Foo generating sources...")
    os.write(
      task.dest / "Foo.scala",
      """package foo
        |object Foo {
        |  final val fooMsg = "Hello World"
        |  def x: Seq[String] = Nil // make sure the compiled code changes between Scala 2.12/2.13
        |  def main(args: Array[String]): Unit = {
        |    println("Foo " + fooMsg)
        |  }
        |}""".stripMargin
    )
    Seq(PathRef(task.dest))
  }

  def compile = task {
    println("Foo compiling...")
    super.compile()
  }

  def run(args: Task[Args] = task.anon(Args())) = task.command {
    println("Foo running..." + args().value.mkString(" "))
    super.run(args)()
  }

  def assembly = task {
    println("Foo assembly...")
    super.assembly()
  }
}

object bar extends ScalaModule {
  def moduleDeps = Seq(foo)
  def scalaVersion = "2.13.8"

  def sources = task {
    println("Bar generating sources...")
    os.write(
      task.dest / "Bar.scala",
      """package bar
        |object Bar {
        |  def main(args: Array[String]): Unit = {
        |    println("Bar " + foo.Foo.fooMsg)
        |  }
        |}""".stripMargin
    )
    Seq(PathRef(task.dest))
  }

  def compile = task {
    println("Bar compiling...")
    super.compile()
  }

  def assembly = task {
    println("Bar assembly...")
    super.assembly()
  }
}

object qux extends ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = task {
    println("Qux generating sources...")
    os.write(
      task.dest / "Qux.scala",
      """package qux
        |object Qux {
        |  def main(args: Array[String]): Unit = {
        |    println("Qux Hello World")
        |  }
        |}""".stripMargin
    )
    Seq(PathRef(task.dest))
  }

  def compile = task {
    println("Qux compiling...")
    super.compile()
  }

  def assembly = task {
    println("Qux assembly...")
    super.assembly()
  }
}