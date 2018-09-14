import cats._, cats.data._, cats.implicits._

trait Context

object Main {
  def foo(f: implicit Int => Int): Int = {
    implicit val x: Int = 1
    f
  }

  def main(args: Array[String]): Unit = {
    val x = Applicative[List].pure(1)
    assert(x == List(1))
    val value = foo(implicit x => x + 1)
    assert(value == 2)
  }
}
