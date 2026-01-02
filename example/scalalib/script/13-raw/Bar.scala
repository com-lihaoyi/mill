//| extends: mill.script.ScalaModule.Raw

def main(args: Array[String]): Unit = {
  println(java.nio.file.Files.readString(java.nio.file.Path.of("file.txt")))
}
