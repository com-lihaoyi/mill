package mill.scalalib

import mill.api.PathRef

/**
 * A cross-platform [[SbtModule]] that supports multiple source root folders each having a sbt
 * compatible directory layout.
 *
 * For `sbt-crossproject` plugin layout, use one of the following presets:
 *  - [[SbtPlatformModule.CrossTypeFull]]
 *  - [[SbtPlatformModule.CrossTypePure]]
 *  - [[SbtPlatformModule.CrossTypeDummy]]
 */
trait SbtPlatformModule extends PlatformScalaModule with SbtModule { outer =>

  protected def sourcesRootFolders = Seq(os.sub, os.sub / platformCrossSuffix)
  override def sourcesFolders =
    sourcesRootFolders.flatMap(root => super.sourcesFolders.map(root / _))
  override def resources =
    sourcesRootFolders.map(root => PathRef(moduleDir / root / "src/main/resources"))

  trait SbtPlatformTests extends SbtTests {

    override def sourcesFolders = outer.sourcesRootFolders.flatMap(root =>
      super.sourcesFolders.map(root / _)
    )
    override def resources = outer.sourcesRootFolders.map(root =>
      PathRef(moduleDir / root / "src" / testModuleName / "resources")
    )
  }
}
object SbtPlatformModule {

  private def crossPartialRootFolders(platformCrossSuffix: String, platforms: String*) =
    platforms.diff(platformCrossSuffix).iterator
      .map(platform => os.SubPath(Seq(platformCrossSuffix, platform).sorted.mkString("-")))
      .toSeq

  /**
   * A [[SbtPlatformModule]] with a layout corresponding to `sbtcrossproject.CrossType.Full`.
   */
  trait CrossTypeFull extends SbtPlatformModule {
    override def sourcesRootFolders = Seq(
      os.sub / "shared",
      os.sub / platformCrossSuffix
    ) ++ crossPartialRootFolders(platformCrossSuffix, "js", "jvm", "native")
  }

  /**
   * A [[SbtPlatformModule]] with a layout corresponding to `sbtcrossproject.CrossType.Pure`.
   */
  trait CrossTypePure extends SbtPlatformModule {
    override def sourcesRootFolders =
      Seq(os.sub) ++ crossPartialRootFolders(platformCrossSuffix, ".js", ".jvm", ".native")
  }

  /**
   * A [[SbtPlatformModule]] with a layout corresponding to `sbtcrossproject.CrossType.Dummy`.
   */
  trait CrossTypeDummy extends SbtPlatformModule {
    override def sourcesRootFolders = Seq(os.sub / platformCrossSuffix)
  }
}
