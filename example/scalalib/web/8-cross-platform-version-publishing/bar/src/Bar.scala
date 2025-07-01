package bar
import scalatags.Text.all._
object Bar {
  val value = p("world", " ", BarVersionSpecific.text())
}
