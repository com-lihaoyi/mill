//| mvnDeps:
//| - com.lihaoyi::scalatags:0.13.1
import scalatags.Text.all.*

def generateHtml(text: String) = {
  h1(text).toString
}

@main
def main(text: String) = {
  println(generateHtml(text))
}
