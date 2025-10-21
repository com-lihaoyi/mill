//| mvnDeps: [com.lihaoyi::scalatags:0.12.0]
package build
import scalatags.Text.all._

def main(args: Array[String]): Unit = {
  println(
    div(
      h1("Hello"),
      p("World")
    ).render

  )
}
