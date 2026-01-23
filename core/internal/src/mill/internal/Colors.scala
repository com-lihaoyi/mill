package mill.internal

case class Colors(
    info: fansi.Attrs,
    warn: fansi.Attrs,
    error: fansi.Attrs,
    success: fansi.Attrs,
    highlight: fansi.Attrs
)
object Colors {
  object Default
      extends Colors(
        fansi.Color.Blue,
        fansi.Color.Yellow,
        fansi.Color.Red,
        fansi.Color.Green,
        fansi.Color.Cyan
      )
  object BlackWhite
      extends Colors(
        fansi.Attrs.Empty,
        fansi.Attrs.Empty,
        fansi.Attrs.Empty,
        fansi.Attrs.Empty,
        fansi.Attrs.Empty
      )
}
