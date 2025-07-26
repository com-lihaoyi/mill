package hello

import java.nio.file.Files
import java.nio.file.Paths

object Main {
  def main(args: Array[String]): Unit = {
    val path = Paths.get(args(0))
    val version = System.getProperty("java.version")
    Files.writeString(path, version.indent(2))
  }
}
