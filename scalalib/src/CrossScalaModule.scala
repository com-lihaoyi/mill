package mill.scalalib

import mill.T

/**
  * A [[ScalaModule]] with is suited to be used with [[mill.define.Cross]].
  * It supports additional source directories with the scala version pattern as suffix (`src-{scalaversionprefix}`),
  * e.g.
  * - src
  * - src-2.11
  * - src-2.12.3
  */
trait CrossScalaModule extends ScalaModule with CrossModuleBase { outer =>
  override def sources = T.sources {
    super.sources() ++
      CrossModuleBase
        .scalaVersionPaths(crossScalaVersion, s => millSourcePath / s"src-$s")
  }

  trait CrossScalaModuleTests extends ScalaModuleTests {
    override def sources = T.sources {
      super.sources() ++
        CrossModuleBase
          .scalaVersionPaths(crossScalaVersion, s => millSourcePath / s"src-$s")
    }
  }
  trait Tests extends CrossScalaModuleTests
}
