package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW, readwriter}

case class PackageSpec(dir: os.SubPath, module: ModuleSpec)
object PackageSpec {
  implicit val rwSubPath: ReadWriter[os.SubPath] =
    readwriter[String].bimap(_.toString, os.SubPath(_))
  implicit val rw: ReadWriter[PackageSpec] = macroRW

  def root(dir: os.SubPath, children: Seq[ModuleSpec] = Nil): PackageSpec =
    apply(dir, ModuleSpec(dir.lastOpt.getOrElse(os.pwd.last), children = children))
}
