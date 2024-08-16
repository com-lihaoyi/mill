//// SNIPPET:BUILD
import mill._, javalib._

object foo extends RootModule with JavaModule {
  def unmanagedClasspath = Task {
    os.write(
      Task.dest / "fastjavaio.jar",
      requests.get.stream(
        "https://github.com/williamfiset/FastJavaIO/releases/download/1.1/fastjavaio.jar"
      )
    )
    Agg(PathRef(Task.dest / "fastjavaio.jar"))
  }
}

