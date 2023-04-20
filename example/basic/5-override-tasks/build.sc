// == Overriding Tasks

import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"

  def sources = T{
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

  def run(args: String*) = T.command {
    println("Running..." + args.mkString(" "))
    super.run(args:_*)
  }
}

// You can re-define targets and commands to override them, and use `super` if you
// want to refer to the originally defined task. The above example shows how to
// override `compile` and `run` to add additional logging messages, but you can
// also override `ScalaModule#generatedSources` to feed generated code to your
// compiler, `ScalaModule#prependShellScript` to make your assemblies executable,
// or `ScalaModule#console` to use the Ammonite REPL instead of the normal Scala
// REPL.
//
// Note that plain `T{...}` targets, `T.sources`, and `T.input`s can override
// each other. e.g. In the example above, we override `sources` which was
// `T.sources` for the `src/` folder with a plain target that generates the
// necessary source files on-the-fly.
//
// In Mill builds the `override` keyword is optional.

/** Usage

> ./mill run
Compiling...
Running...
Hello World

*/