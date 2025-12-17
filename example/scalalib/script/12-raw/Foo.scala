//| extends: [mill.script.ScalaModule.Raw]

def main(args: Array[String]): Unit = {
  println(os.read(os.pwd / "file.txt"))
}
