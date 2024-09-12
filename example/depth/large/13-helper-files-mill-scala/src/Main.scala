object Main {
  def main(args: Array[String]): Unit = {
    println("Main Env build.util.myScalaVersion: " + sys.env("MY_SCALA_VERSION"))
    println("Main Env build.foo.versions.myProjectVersion: " + sys.env("MY_PROJECT_VERSION"))
  }
}
