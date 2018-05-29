import $file.inner.build
import mill._
import ammonite.ops._

trait PathAware extends mill.Module {
  def selfPath = T { millSourcePath }
}

trait DestAware extends mill.Module {
  def selfDest = T { T.ctx().dest / up / up }
}

object sub extends PathAware with DestAware {
  object sub extends PathAware with DestAware
}

