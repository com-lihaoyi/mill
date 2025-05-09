package mill.spotless

trait PathResolver {
  def path(rel: os.RelPath): Option[os.Path]
}
object PathResolver {

  def apply(roots: Seq[os.Path]): PathResolver =
    Impl(roots.distinct)

  private class Impl(roots: Seq[os.Path]) extends PathResolver {
    def path(rel: os.RelPath) = roots.iterator.map(_ / rel).find(os.exists)
  }
}
