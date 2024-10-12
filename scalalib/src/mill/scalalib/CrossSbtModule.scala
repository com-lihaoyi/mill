package mill.scalalib

import mill.api.PathRef
import mill.{T, Task}

import scala.annotation.nowarn

trait CrossSbtModule extends SbtModule with CrossModuleBase { outer =>

  override def sources: T[Seq[PathRef]] = Task.Sources {
    super.sources() ++ scalaVersionDirectoryNames.map(s =>
      PathRef(millSourcePath / "src/main" / s"scala-$s")
    )
  }

  @nowarn
  type CrossSbtTests = CrossSbtModuleTests
  @deprecated("Use CrossSbtTests instead", since = "Mill 0.11.10")
  trait CrossSbtModuleTests extends SbtTests {
    override def millSourcePath = outer.millSourcePath
    override def sources = Task.Sources {
      super.sources() ++ scalaVersionDirectoryNames.map(s =>
        PathRef(millSourcePath / "src/test" / s"scala-$s")
      )
    }
  }
  @deprecated("Use CrossTests instead", since = "Mill after 0.12.0-RC1")
  trait Tests extends CrossSbtModuleTests
}
