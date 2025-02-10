package mill.exec

private[mill] case class Cached(value: ujson.Value, valueHash: Int, inputsHash: Int)

private object Cached {
  implicit val rw: upickle.default.ReadWriter[Cached] = upickle.default.macroRW
}
