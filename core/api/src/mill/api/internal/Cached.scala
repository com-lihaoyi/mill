package mill.api.internal

private[mill] case class Cached(
    value: ujson.Value,
    valueHash: Int,
    inputsHash: Int,
    millVersion: String = "",
    millJvmVersion: String = ""
)

private[mill] object Cached {
  implicit val rw: upickle.ReadWriter[Cached] = upickle.macroRW
}
