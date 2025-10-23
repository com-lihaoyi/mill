//| jvmId: 11.0.28
//| mvnDeps:
//| - "com.lihaoyi::scalatags:0.13.1"
//| - "com.lihaoyi::mainargs:0.7.7"
import scalatags.Text.all.*
import mainargs.{main, Parser}

def generateHtml(text: String) = {
  h1(text).toString
}

@main
def main(text: String) = {
  println("Jvm Version: " + System.getProperty("java.version"))
  println(generateHtml(text))
}

def main(args: Array[String]): Unit = Parser(this).runOrExit(args)
