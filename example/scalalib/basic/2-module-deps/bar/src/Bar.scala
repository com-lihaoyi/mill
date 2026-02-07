package bar
import scalatags.Text.all.*
object Bar {
  def generateHtml(text: String) = {
    val value = h1(text)
    value.toString
  }
}
