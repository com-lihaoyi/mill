package models

case class Foo(name: String) {
  def greet: String = s"hello, $name"
}
