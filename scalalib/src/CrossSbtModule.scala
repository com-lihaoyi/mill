package mill.scalalib

import mill.api.PathRef
import mill.T
import mill.scalalib.{CrossModuleBase, SbtModule}

trait CrossSbtModule extends SbtModule with CrossModuleBase { outer =>

  override def sources = T.sources {
    super.sources() ++ scalaVersionDirectoryNames.map(s =>
      PathRef(millSourcePath / "src" / "main" / s"scala-$s")
    )
  }
  trait CrossSbtModuleTests extends SbtModuleTests {
    override def millSourcePath = outer.millSourcePath
    override def sources = T.sources {
      super.sources() ++ scalaVersionDirectoryNames.map(s =>
        PathRef(millSourcePath / "src" / "test" / s"scala-$s")
      )
    }
  }
  trait Tests extends CrossSbtModuleTests
}
