package mill.api.internal

private[mill] case class Cached(
    value: ujson.Value,
    valueHash: Int,
    inputsHash: Int,
    @com.lihaoyi.unroll millVersion: String = "",
    @com.lihaoyi.unroll millJvmVersion: String = ""
)

private[mill] object Cached {
  implicit val rw: upickle.ReadWriter[Cached] = upickle.macroRW
}
