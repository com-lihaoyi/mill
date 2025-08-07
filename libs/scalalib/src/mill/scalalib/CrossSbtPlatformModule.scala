package mill.scalalib

/**
 * A [[SbtPlatformModule]] suited to be used with [[mill.api.Cross]]. It supports additional source
 * directories with the scala version pattern as suffix (src/main/scala-{scalaversionprefix}), e.g.
 *  - src/main/scala-2
 *  - src/main/scala-2.12
 *  - src/main/scala-2.13
 * @note Use with [[CrossScalaVersionRanges]] to add version range specific sources.
 */
trait CrossSbtPlatformModule extends CrossSbtModule with SbtPlatformModule { outer =>
  override def versionSourcesPaths = sourcesRootFolders.flatMap(root =>
    scalaVersionDirectoryNames.map(s => root / "src/main" / s"scala-$s")
  )

  trait CrossSbtPlatformTests extends CrossSbtTests with SbtPlatformTests {
    override def versionSourcesPaths = outer.sourcesRootFolders.flatMap(root =>
      scalaVersionDirectoryNames.map(s => root / "src" / testModuleName / s"scala-$s")
    )
  }
}
