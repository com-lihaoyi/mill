import cats._, cats.data._, cats.implicits._

trait Context

object Main {
  def foo(f: given Int => Int): Int = {
    given x as Int = 1
    f
  }

  def main(args: Array[String]): Unit = {
    val x = Applicative[List].pure(1)
    assert(x == List(1))
    val value = foo(given x => x + 1)
    assert(value == 2)
  }
}
