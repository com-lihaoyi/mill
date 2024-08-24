//// SNIPPET:BUILD1
import mill._, javalib._

object foo extends JavaModule {

  def sources = T{
    os.write(
      T.dest / "Foo.java",
      """package foo;
        |
        |public class Foo {
        |    public static void main(String[] args) {
        |        System.out.println("Hello World");
        |    }
        |}
      """.stripMargin
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
//// SNIPPET:BUILD2

object foo2 extends JavaModule {
  def generatedSources = T{
    os.write(T.dest / "Foo.java", """...""")
    Seq(PathRef(T.dest))
  }
}

object foo3 extends JavaModule {
  def sources = T{
    os.write(T.dest / "Foo.java", """...""")
    super.sources() ++ Seq(PathRef(T.dest))
  }
}
