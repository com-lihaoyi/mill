package mill.main.sbt

import upickle.default.{ReadWriter => RW, macroRW}

case class ProjectTree()
object ProjectTree {
  implicit val rw: RW[ProjectTree] = macroRW
}
