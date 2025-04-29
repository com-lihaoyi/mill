package mill.spotless

trait PathResolver {
  def path(rel: os.RelPath): Option[os.Path]
}
object PathResolver {

  def apply(root: os.Path): PathResolver =
    new Impl(root)

  private class Impl(root: os.Path) extends PathResolver {
    def path(rel: os.RelPath): Option[os.Path] = {
      val path = root / rel
      Option.when(os.exists(path))(path)
    }
  }
}
