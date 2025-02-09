package mill.scalalib

import mill.api.PathRef
import mill.{T, Task}

trait CrossSbtModule extends SbtModule with CrossModuleBase { outer =>

  override def sources: T[Seq[PathRef]] = Task.Sources {
    super.sources() ++ scalaVersionDirectoryNames.map(s =>
      PathRef(moduleBase / "src/main" / s"scala-$s")
    )
  }

  trait CrossSbtTests extends SbtTests {
    override def moduleBase = outer.moduleBase
    override def sources = Task.Sources {
      super.sources() ++ scalaVersionDirectoryNames.map(s =>
        PathRef(moduleBase / "src/test" / s"scala-$s")
      )
    }
  }
}
