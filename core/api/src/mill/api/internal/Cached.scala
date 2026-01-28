package mill.api.internal

private[mill] case class Cached(
    value: ujson.Value,
    valueHash: Int,
    inputsHash: Int,
    millVersion: String = "",
    millJvmVersion: String = "",
    // Hash of the classloader (Mill jars + build dependencies), 0 means not tracked (old cache)
    classLoaderSigHash: Int = 0
)

private[mill] object Cached {
  implicit val rw: upickle.ReadWriter[Cached] = upickle.macroRW
}
