//// SNIPPET:BUILD1
import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = Task {
    os.write(
      Task.dest / "Foo.scala",
      """package foo
        |object Foo {
        |  def main(args: Array[String]): Unit = {
        |    println("Hello World")
        |  }
        |}
      """.stripMargin
    )
    Seq(PathRef(Task.dest))
  }

  def compile = Task {
    println("Compiling...")
    super.compile()
  }

  def run(args: Task[Args] = Task.anon(Args())) = Task.command {
    println("Running..." + args().value.mkString(" "))
    super.run(args)()
  }
}

//// SNIPPET:END

// You can re-define targets and commands to override them, and use `super` if you
// want to refer to the originally defined Task. The above example shows how to
// override `compile` and `run` to add additional logging messages, and we
// override `sources` which was `Task.sources` for the `src/` folder with a plain
// `Task {...}` target that generates the  necessary source files on-the-fly.
//
// Note that this example *replaces* your `src/` folder with the generated
// sources. If you want to *add* generated sources, you can either override
// `generatedSources`, or you can override `sources` and use `super` to
// include the original source folder:

//// SNIPPET:BUILD2

object foo2 extends ScalaModule {
  def scalaVersion = "2.13.8"

  def generatedSources = Task {
    os.write(Task.dest / "Foo.scala", """...""")
    Seq(PathRef(Task.dest))
  }
}

object foo3 extends ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = Task {
    os.write(Task.dest / "Foo.scala", """...""")
    super.sources() ++ Seq(PathRef(Task.dest))
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