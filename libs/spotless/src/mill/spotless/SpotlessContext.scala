package mill.spotless

import com.diffplug.spotless.Provisioner

trait SpotlessContext {
  def path(rel: os.RelPath): Option[os.Path]
  def provisioner: Provisioner
}
object SpotlessContext {

  def apply(roots: Seq[os.Path], provisioner: Provisioner): SpotlessContext =
    Impl(roots.distinct, provisioner)

  private class Impl(roots: Seq[os.Path], val provisioner: Provisioner)
      extends SpotlessContext {
    def path(rel: os.RelPath) = roots.iterator.map(_ / rel).find(os.exists)
  }
}
