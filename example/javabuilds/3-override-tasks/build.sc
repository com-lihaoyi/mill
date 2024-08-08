//// SNIPPET:BUILD1
import mill._, javalib._

object foo extends JavaModule {

  def sources = T{
    os.write(
      task.dest / "Foo.java",
      """package foo;
        |
        |public class Foo {
        |    public static void main(String[] args) {
        |        System.out.println("Hello World");
        |    }
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
//// SNIPPET:BUILD2

object foo2 extends JavaModule {
  def generatedSources = T{
    os.write(task.dest / "Foo.java", """...""")
    Seq(PathRef(task.dest))
  }
}

object foo3 extends JavaModule {
  def sources = T{
    os.write(task.dest / "Foo.java", """...""")
    super.sources() ++ Seq(PathRef(task.dest))
  }
}
