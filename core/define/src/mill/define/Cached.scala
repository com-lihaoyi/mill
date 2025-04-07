package mill.define

private[mill] case class Cached(value: ujson.Value, valueHash: Int, inputsHash: Int)

private[mill] object Cached {
  implicit val rw: upickle.default.ReadWriter[Cached] = upickle.default.macroRW
}
