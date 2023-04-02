import $file.inner.build
import mill._

trait PathAware extends mill.Module {
  def selfPath = T { millSourcePath }
}

trait DestAware extends mill.Module {
  def selfDest = T { T.dest / os.up }
}

object sub extends PathAware with DestAware {
  object sub extends PathAware with DestAware
}

object sourcepathmod extends mill.Module {
  def selfDest = T { T.dest / os.up }

  object jvm extends mill.Module {
    def selfDest = T { T.dest / os.up }
    def millSourcePath = sourcepathmod.millSourcePath
    def sources = T.sources(millSourcePath / "src", millSourcePath / "src-jvm")
  }

  object js extends mill.Module {
    def selfDest = T { T.dest / os.up }
    def millSourcePath = sourcepathmod.millSourcePath
    def sources = T.sources(millSourcePath / "src", millSourcePath / "src-js")
  }
}
