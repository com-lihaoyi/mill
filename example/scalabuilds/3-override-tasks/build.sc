//// SNIPPET:BUILD1
import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = T{
    os.write(
      task.dest / "Foo.scala",
      """package foo
        |object Foo {
        |  def main(args: Array[String]): Unit = {
        |    println("Hello World")
        |  }
        |}
      """.stripMargin
    )
    Seq(PathRef(task.dest))
  }

  def compile = task {
    println("Compiling...")
    super.compile()
  }

  def run(args: Task[Args] = task.anon(Args())) = task.command {
    println("Running..." + args().value.mkString(" "))
    super.run(args)()
  }
}

//// SNIPPET:END

// You can re-define targets and commands to override them, and use `super` if you
// want to refer to the originally defined task. The above example shows how to
// override `compile` and `run` to add additional logging messages, and we
// override `sources` which was `task.sources` for the `src/` folder with a plain
// `T{...}` target that generates the  necessary source files on-the-fly.
//
// Note that this example *replaces* your `src/` folder with the generated
// sources. If you want to *add* generated sources, you can either override
// `generatedSources`, or you can override `sources` and use `super` to
// include the original source folder:

//// SNIPPET:BUILD2

object foo2 extends ScalaModule {
  def scalaVersion = "2.13.8"

  def generatedSources = T{
    os.write(task.dest / "Foo.scala", """...""")
    Seq(PathRef(task.dest))
  }
}

object foo3 extends ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = T{
    os.write(task.dest / "Foo.scala", """...""")
    super.sources() ++ Seq(PathRef(task.dest))
  }
}

//// SNIPPET:END

// In Mill builds the `override` keyword is optional.

/** Usage

> mill foo.run
Compiling...
Running...
Hello World

*/