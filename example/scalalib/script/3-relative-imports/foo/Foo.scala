//| moduleDeps: [bar/Bar.scala]
//| scalaVersion: 3.7.1
//| mvnDeps:
//| - com.lihaoyi::mainargs:0.7.7


package foo
import mainargs.{main, Parser, arg}
object Foo {
@main
def main(text: String): Unit = {
println(bar.Bar.generateHtml(text))
  }

def main(args: Array[String]): Unit = Parser(this).runOrExit(args)
}
