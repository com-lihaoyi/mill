package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW, readwriter}

case class PackageSpec(dir: os.SubPath, module: ModuleSpec) {
  def modulesBySegments: Seq[(Seq[String], ModuleSpec)] = {
    def recurse(segments: Seq[String], module: ModuleSpec): Seq[(Seq[String], ModuleSpec)] =
      (segments, module) +: module.children.flatMap(module =>
        recurse(segments :+ module.name, module)
      )
    recurse(dir.segments, module)
  }
}
object PackageSpec {
  implicit val rwSubPath: ReadWriter[os.SubPath] =
    readwriter[String].bimap(_.toString, os.SubPath(_))
  implicit val rw: ReadWriter[PackageSpec] = macroRW

  def root(dir: os.SubPath): PackageSpec =
    apply(dir, ModuleSpec(dir.lastOpt.getOrElse(os.pwd.last)))
}
