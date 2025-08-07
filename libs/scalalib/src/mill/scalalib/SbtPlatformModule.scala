package mill.scalalib

import mill.api.PathRef

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

  private def crossPartials(platformCrossSuffix: String, platforms: String*) =
    platforms.diff(platformCrossSuffix).iterator
      .map(platform => os.SubPath(Seq(platformCrossSuffix, platform).sorted.mkString("-")))
      .toSeq

  trait CrossTypeFull extends SbtPlatformModule {
    override def sourcesRootFolders = Seq(
      os.sub / "shared",
      os.sub / platformCrossSuffix
    ) ++ crossPartials(platformCrossSuffix, "js", "jvm", "native")
  }

  trait CrossTypePure extends SbtPlatformModule {
    override def sourcesRootFolders =
      Seq(os.sub) ++ crossPartials(platformCrossSuffix, ".js", ".jvm", ".native")
  }

  trait CrossTypeDummy extends SbtPlatformModule {
    override def sourcesRootFolders = Seq(os.sub / platformCrossSuffix)
  }
}
