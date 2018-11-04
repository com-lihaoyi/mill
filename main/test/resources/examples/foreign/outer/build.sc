import $file.inner.build
import mill._

trait PathAware extends mill.Module {
  def selfPath = T { millSourcePath }
}

trait DestAware extends mill.Module {
  def selfDest = T { T.ctx().dest / os.up / os.up }
}

object sub extends PathAware with DestAware {
  object sub extends PathAware with DestAware
}

