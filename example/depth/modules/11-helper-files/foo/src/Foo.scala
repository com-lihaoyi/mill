package foo

object Foo {
  def main(args: Array[String]): Unit = {
    println("build.util.scalaVersion: " + sys.env("SCALA_VERSION"))
    println("util.scalaVersion: " + sys.env("SCALA_VERSION2"))
    println("build.foo.versions.projectVersion: " + sys.env("PROJECT_VERSION"))
    println("versions.projectVersion: " + sys.env("PROJECT_VERSION2"))
  }
}
