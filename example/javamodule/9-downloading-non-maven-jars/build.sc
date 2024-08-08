//// SNIPPET:BUILD
import mill._, javalib._

object foo extends RootModule with JavaModule {
  def unmanagedClasspath = task {
    os.write(
      task.dest / "fastjavaio.jar",
      requests.get.stream(
        "https://github.com/williamfiset/FastJavaIO/releases/download/1.1/fastjavaio.jar"
      )
    )
    Agg(PathRef(task.dest / "fastjavaio.jar"))
  }
}

