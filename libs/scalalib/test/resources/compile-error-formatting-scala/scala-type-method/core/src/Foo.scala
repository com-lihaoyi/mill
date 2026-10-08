package scalaTypeMethod

object Foo {
  def test(): Unit = {
    val s = "hello"
    s.nonExistentMethod()
  }
}
