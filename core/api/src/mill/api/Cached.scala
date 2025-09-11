package mill.api

private[mill] case class Cached(value: ujson.Value, valueHash: Int, inputsHash: Int)

private[mill] object Cached {
  implicit val rw: upickle.ReadWriter[Cached] = upickle.macroRW
}
