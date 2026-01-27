import monocle.macros._
@Lenses case class Foo(bar: String)
object Main extends App {
  println(Foo.bar.get(Foo("bar")))
}
