import java.nio.file.{Files, Paths}

object Main {
  def main(args: Array[String]): Unit = {
    val resultPath = Paths.get(args(0))
    Files.createDirectories(resultPath.getParent)
    Files.write(
      resultPath,
      s"TEST_ENV_VARIABLE=${sys.env.getOrElse("TEST_ENV_VARIABLE", "")}".getBytes
    )
  }
}
