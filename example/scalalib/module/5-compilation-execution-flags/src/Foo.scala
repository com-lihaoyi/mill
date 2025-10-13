package foo
object Foo {
  def main(args: Array[String]): Unit = {
    println(sys.props("my.jvm.property") + " " + sys.env("MY_ENV_VAR"))
  }
}
