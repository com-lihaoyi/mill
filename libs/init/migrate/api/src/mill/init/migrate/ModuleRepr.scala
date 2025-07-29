package mill.init.migrate

import upickle.default.{ReadWriter, macroRW}

case class ModuleRepr(main: ModuleTypedef = ModuleTypedef(), test: Option[ModuleTypedef] = None)
object ModuleRepr {
  implicit val rw: ReadWriter[ModuleRepr] = macroRW
}
