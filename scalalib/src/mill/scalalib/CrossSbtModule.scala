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

  trait CrossSbtTests extends SbtTests {
    override def millSourcePath = outer.millSourcePath
    override def sources = Task.Sources {
      super.sources() ++ scalaVersionDirectoryNames.map(s =>
        PathRef(millSourcePath / "src/test" / s"scala-$s")
      )
    }
  }
}
