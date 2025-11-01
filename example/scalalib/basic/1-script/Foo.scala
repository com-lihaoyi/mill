//| jvmId: 11.0.28
//| mvnDeps:
//| - "com.lihaoyi::scalatags:0.13.1"
import scalatags.Text.all.*

def generateHtml(text: String) = {
  h1(text).toString
}

@main
def main(text: String) = {
  println("Jvm Version: " + System.getProperty("java.version"))
  println(generateHtml(text))
}

@main
def main2(text: String) = {
  println("Jvm Version: " + System.getProperty("java.version"))
  println(generateHtml(text))
}

@main
def lols(text: String) = {
  println("Jvm Version: " + System.getProperty("java.version"))
  println(generateHtml(text))
}
