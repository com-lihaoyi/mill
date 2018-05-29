import scala.collection.immutable.SortedMap

object Main extends App {
  def foo[F[_], A](fa: F[A]): String = fa.toString
  foo { x: Int => x * 2 }
}
