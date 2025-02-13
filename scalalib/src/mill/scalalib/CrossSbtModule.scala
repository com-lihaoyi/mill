package mill.scalalib

import mill.api.PathRef
import mill.{T, Task}

trait CrossSbtModule extends SbtModule with CrossModuleBase { outer =>

  def versionSources = Task.Sources(
    scalaVersionDirectoryNames.map(s => os.sub / "src/main" / s"scala-$s")*
  )
  override def sources: T[Seq[PathRef]] = Task {
    super.sources() ++ versionSources()
  }

  trait CrossSbtTests extends SbtTests {
    override def moduleDir = outer.moduleDir

    def versionSources = Task.Sources(
      scalaVersionDirectoryNames.map(s => os.sub / "src/main" / s"scala-$s") *
    )
    override def sources = Task {
      super.sources() ++ versionSources()
    }
  }
}
