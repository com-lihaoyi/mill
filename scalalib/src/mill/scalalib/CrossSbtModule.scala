package mill.scalalib

import mill.api.PathRef
import mill.{T, Task}

trait CrossSbtModule extends SbtModule with CrossModuleBase { outer =>

  def versionSourcesPaths = scalaVersionDirectoryNames.map(s => os.sub / "src/main" / s"scala-$s")
  def versionSources = Task.Sources(versionSourcesPaths*)
  override def sources: T[Seq[PathRef]] = Task { super.sources() ++ versionSources() }

  trait CrossSbtTests extends SbtTests {
    override def moduleDir = outer.moduleDir

    def versionSourcesPaths = scalaVersionDirectoryNames.map(s => os.sub / "src/main" / s"scala-$s")
    def versionSources = Task.Sources(versionSourcesPaths*)
    override def sources = Task { super.sources() ++ versionSources() }
  }
}
