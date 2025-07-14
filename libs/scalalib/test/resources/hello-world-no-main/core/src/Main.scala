import scala.collection._
import java.nio.file.{Files, Paths}

import Main.{args, greeting}
object Main0 {
  def apply(s: String, greeting: String) = {
    val resultPath = Paths.get(s)
    Files.createDirectories(resultPath.getParent)
    Files.write(resultPath, greeting.getBytes)
  }
}
