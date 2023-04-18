package mill.scalalib

import mill.api.PathRef
import mill.T

/**
 * A [[ScalaModule]] which is suited to be used with [[mill.define.Cross]].
 * It supports additional source directories with the scala version pattern
 * as suffix (`src-{scalaversionprefix}`), e.g.
 *
 * - src
 * - src-2.11
 * - src-2.12.3
 */
trait CrossScalaModule extends ScalaModule with CrossModuleBase { outer =>
  override def sources = T.sources {
    super.sources() ++
      scalaVersionDirectoryNames.map(s => PathRef(millSourcePath / s"src-$s"))
  }

  trait CrossScalaModuleTests extends ScalaModuleTests {
    override def sources = T.sources {
      super.sources() ++
        scalaVersionDirectoryNames.map(s => PathRef(millSourcePath / s"src-$s"))
    }
  }
  trait Tests extends CrossScalaModuleTests
}

object CrossScalaModule {

  /**
   * Used with a [[mill.define.Cross]] when you want [[CrossScalaModule]]'s
   * nested within it
   */
  trait Base extends mill.Cross.Module[String] {
    def crossScalaVersion: String = crossValue

    private def wrapperSegments0 = millModuleSegments.parts

    trait CrossScalaModule extends mill.scalalib.CrossScalaModule {
      override def wrapperSegments = wrapperSegments0
      def crossValue = Base.this.crossScalaVersion
    }
  }
}
