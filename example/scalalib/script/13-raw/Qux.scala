//| extends: mill.script.ScalaModule.Raw
//| mvnDeps: [com.lihaoyi::os-lib:0.11.4]

def main(args: Array[String]): Unit = {
  println(os.read(os.pwd / "file.txt"))
}
