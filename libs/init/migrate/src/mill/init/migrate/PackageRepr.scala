package mill.init.migrate

case class PackageRepr(segments: Seq[String], modules: Tree[ModuleRepr])
object PackageRepr {

  def empty(segments: Seq[String]) = PackageRepr(segments, Tree(ModuleRepr()))
}
