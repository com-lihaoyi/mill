package mill.scalajslib

object NodeJSConfig {
  import upickle.default.{ReadWriter => RW, macroRW}
  implicit def rw: RW[NodeJSConfig] = macroRW
}

final case class NodeJSConfig(executable: String = "node",
                              args: List[String] = Nil,
                              env: Map[String, String] = Map.empty,
                              sourceMap: Boolean = true)
