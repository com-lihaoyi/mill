package mill.internal


trait Watchable {
  def poll(): Long
  def signature: Long
  def validate(): Boolean = poll() == signature
}
object Watchable{
  case class Path(p: mill.api.PathRef) extends Watchable {
    def poll() = p.recomputeSig()
    def signature = p.sig
  }
  case class Value(f: () => Long, signature: Long) extends Watchable {
    def poll() = f()
  }
}
