package mill.util

case class Colors(info: fansi.Attrs, error: fansi.Attrs)
object Colors{
  object Default extends Colors(fansi.Color.Blue, fansi.Color.Red)
  object BlackWhite extends Colors(fansi.Attrs.Empty, fansi.Attrs.Empty)
}
