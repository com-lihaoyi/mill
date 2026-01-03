package scalaTypeMismatch

object Foo {
  def test(): Unit = {
    val x: Int = "hello"
  }
}
