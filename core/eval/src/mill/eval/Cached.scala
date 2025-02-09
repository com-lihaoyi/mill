package mill.eval

case class Cached(value: ujson.Value, valueHash: Int, inputsHash: Int)

object Cached {
  implicit val rw: upickle.default.ReadWriter[Cached] = upickle.default.macroRW
}