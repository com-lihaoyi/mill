package mill.internal

private[mill] case class Colors(info: fansi.Attrs, warn: fansi.Attrs, error: fansi.Attrs)
private[mill] object Colors {
  object Default extends Colors(fansi.Color.Blue, fansi.Color.Yellow, fansi.Color.Red)
  object BlackWhite extends Colors(fansi.Attrs.Empty, fansi.Attrs.Empty, fansi.Attrs.Empty)
}
