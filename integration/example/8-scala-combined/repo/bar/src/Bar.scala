package bar
import scalatags.Text.all._
object Bar {
  val value = p("world", " ", MajorVersionSpecific.text())
}
