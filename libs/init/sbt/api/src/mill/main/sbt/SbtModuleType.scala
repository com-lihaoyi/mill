package mill.main.sbt

import upickle.default.{ReadWriter, macroRW}

sealed trait SbtModuleType
object SbtModuleType {
  case class Default(baseDir: Seq[String]) extends SbtModuleType
  object Default {
    implicit val rw: ReadWriter[Default] = macroRW
  }
  case class Platform(rootDir: Seq[String]) extends SbtModuleType
  object Platform {
    implicit val rw: ReadWriter[Platform] = macroRW
  }
  implicit val rw: ReadWriter[SbtModuleType] = macroRW
}
