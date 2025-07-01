package bar
import scalatags.Text.all.*
object Bar {
  val value = p("world", " ", BarVersionSpecific.text())
}
