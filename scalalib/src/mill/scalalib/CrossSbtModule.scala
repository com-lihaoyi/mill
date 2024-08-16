package mill.scalalib

import mill.api.PathRef
import mill.{Task, T}
import mill.scalalib.{CrossModuleBase, SbtModule}

trait CrossSbtModule extends SbtModule with CrossModuleBase { outer =>

  override def sources: T[Seq[PathRef]] = Task.sources {
    super.sources() ++ scalaVersionDirectoryNames.map(s =>
      PathRef(millSourcePath / "src" / "main" / s"scala-$s")
    )
  }

  type CrossSbtTests = CrossSbtModuleTests
  @deprecated("Use CrossSbtTests instead", since = "Mill 0.11.10")
  trait CrossSbtModuleTests extends SbtModuleTests {
    override def millSourcePath = outer.millSourcePath
    override def sources = Task.sources {
      super.sources() ++ scalaVersionDirectoryNames.map(s =>
        PathRef(millSourcePath / "src" / "test" / s"scala-$s")
      )
    }
  }
  trait Tests extends CrossSbtModuleTests
}
