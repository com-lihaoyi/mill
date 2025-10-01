package mill.scalalib

import mill.api.PathRef

/**
 * A cross-platform module that can share sources with other cross members.
 * {{{
 *  object foo extends Module {
 *    object js extends ScalaJSModule with SbtPlatformModule
 *    object jvm extends SbtPlatformModule
 *    object native extends ScalaNativeModule with SbtPlatformModule
 *  }
 * }}}
 * The example maps to multiple source root folders, each having a [[SbtModule]] directory layout.
 * {{{
 *  foo           // source root shared by all cross members
 *  ├─js          // source root for js cross member
 *  ├─jvm         // source root for jvm cross member
 *  ├─native      // source root for native cross member
 *  ├─js-jvm      // source root shared by js and jvm cross members
 *  ├─js-native   // source root shared by js and native cross members
 *  └─jvm-native  // source root shared by jvm and native cross members
 * }}}
 * Mix in [[CrossSbtPlatformModule]] for cross Scala version support.
 */
trait SbtPlatformModule extends PlatformScalaModule with SbtModule { outer =>

  def sourcesRootFolders = Seq(os.sub, os.sub / platformCrossSuffix) ++
    Seq("js", "jvm", "native").combinations(2).collect {
      case names if names.contains(platformCrossSuffix) => os.SubPath(names.sorted.mkString("-"))
    }
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
