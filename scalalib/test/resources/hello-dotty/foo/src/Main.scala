trait Context

object Main {
  def foo(f: implicit Int => Int): Int = {
    implicit val x: Int = 1
    f
  }

  def main(args: Array[String]): Unit = {
    val value = foo(implicit x => x + 1)
    assert(value == 2)
  }
}
