package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW, readwriter}

case class PackageSpec(dir: os.SubPath, module: ModuleSpec) {

  def moduleTree: Seq[(os.SubPath, ModuleSpec)] = {
    def recurse(dir: os.SubPath, module: ModuleSpec): Seq[(os.SubPath, ModuleSpec)] =
      (dir, module) +: module.children.flatMap(module =>
        recurse(dir / module.name, module)
      )
    recurse(dir, module)
  }
}
object PackageSpec {
  import ModuleSpec.rwSubPath
  implicit val rw: ReadWriter[PackageSpec] = macroRW

  def root(dir: os.SubPath): PackageSpec =
    apply(dir, ModuleSpec(dir.lastOpt.getOrElse(os.pwd.last)))
}
