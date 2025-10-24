//| scalaVersion: 3.7.1
//| mvnDeps:
//| - com.lihaoyi::scalatags:0.13.1
package bar
import scalatags.Text.all.*
object Bar {
  def generateHtml(text: String) = {
    val value = h1(text)
    value.toString
  }
}
