package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW, readwriter}

case class PackageSpec(moduleDir: os.SubPath, module: ModuleSpec)
object PackageSpec {
  private implicit val rwSubPath: ReadWriter[os.SubPath] =
    readwriter[String].bimap(_.toString, os.SubPath(_))
  implicit val rw: ReadWriter[PackageSpec] = macroRW

  def root(dir: os.SubPath, children: Seq[ModuleSpec] = Nil): PackageSpec =
    apply(dir, ModuleSpec(dir.lastOpt.getOrElse(os.pwd.last), children = children))
}
