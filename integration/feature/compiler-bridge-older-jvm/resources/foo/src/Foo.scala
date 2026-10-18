package foo

object Foo {
  def value: String = java.util.Optional.of("foo").get
}
