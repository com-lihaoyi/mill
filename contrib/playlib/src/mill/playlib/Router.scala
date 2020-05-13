package mill
package playlib

private[playlib] trait Router extends RouterModule with Layout {
  override def routes = T(conf())
}
