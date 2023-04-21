import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"
  def unmanagedClasspath = T {
    os.write(
      T.dest / "fastjavaio.jar",
      requests.get.stream(
        "https://github.com/williamfiset/FastJavaIO/releases/download/1.1/fastjavaio.jar"
      )
    )
    Agg(PathRef(T.dest / "fastjavaio.jar"))
  }
}

// You can also override `unmanagedClasspath` to point it at jars that you want to
// download from arbitrary URLs. Note that targets like `unmanagedClasspath` are
// cached, so your jar is downloaded only once and re-used indefinitely after that.

/** Usage

> ./mill run "textfile.txt"
I am cow
hear me moo
I weigh twice as much as you

*/
