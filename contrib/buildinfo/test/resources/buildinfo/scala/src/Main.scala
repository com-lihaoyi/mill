package foo
import java.nio.file.{Files, Paths}

object Main extends App {

  val resultPath = Paths.get(args(0))
  Files.createDirectories(resultPath.getParent)
  Files.write(resultPath, BuildInfo.scalaVersion.getBytes)

}
