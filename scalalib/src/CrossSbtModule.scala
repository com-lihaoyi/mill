package mill.scalalib

import mill.api.PathRef
import mill.T
import mill.scalalib.{CrossModuleBase, SbtModule}

trait CrossSbtModule extends SbtModule with CrossModuleBase { outer =>

  override def sources = T.sources {
    val crossScalaVersions = millOuterCtx.crossInstances.map(_.asInstanceOf[CrossSbtModule].crossScalaVersion)
    super.sources() ++
      CrossModuleBase.scalaVersionPaths(
        crossScalaVersion,
        crossScalaVersions,
        s => millSourcePath / "src" / "main" / s"scala-$s"
      )

  }
  trait CrossSbtModuleTests extends SbtModuleTests {
    override def millSourcePath = outer.millSourcePath
    override def sources = T.sources {
      val crossScalaVersions = millOuterCtx.crossInstances.map(_.asInstanceOf[CrossSbtModule].crossScalaVersion)
      super.sources() ++
        CrossModuleBase.scalaVersionPaths(
          crossScalaVersion,
          crossScalaVersions,
          s => millSourcePath / "src" / "test" / s"scala-$s"
        )
    }
  }
  trait Tests extends CrossSbtModuleTests
}
