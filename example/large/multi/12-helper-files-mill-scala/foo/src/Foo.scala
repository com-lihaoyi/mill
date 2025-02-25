package foo

object Foo {
  def main(args: Array[String]): Unit = {
    println("Foo Env build.util.myScalaVersion: " + sys.env("MY_SCALA_VERSION"))
    println("Foo Env build.foo.versions.myProjectVersion: " + sys.env("MY_PROJECT_VERSION"))
  }
}
