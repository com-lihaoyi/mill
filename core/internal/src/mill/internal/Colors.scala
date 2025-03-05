package mill.internal

private[mill] case class Colors(info: fansi.Attrs, error: fansi.Attrs)
private[mill] object Colors {
  object Default extends Colors(fansi.Color.Blue, fansi.Color.Red)
  object BlackWhite extends Colors(fansi.Attrs.Empty, fansi.Attrs.Empty)
}
