package mill.scalalib

import mill.api.PathRef
import mill.{T, Task}

trait CrossSbtModule extends SbtModule with CrossModuleBase { outer =>

  override def sources: T[Seq[PathRef]] = Task.Sources {
    super.sources() ++ scalaVersionDirectoryNames.map(s =>
      PathRef(modulePath / "src/main" / s"scala-$s")
    )
  }

  trait CrossSbtTests extends SbtTests {
    override def modulePath = outer.modulePath
    override def sources = Task.Sources {
      super.sources() ++ scalaVersionDirectoryNames.map(s =>
        PathRef(modulePath / "src/test" / s"scala-$s")
      )
    }
  }
}
