package mill.scalalib

/**
 * A cross-platform and cross-version module that extends the [[SbtPlatformModule]] layout with
 * version specific folders like
 *  - `src/main/scala-2`
 *  - `src/main/scala-2.12`
 *  - `src/main/scala-2.12.20`
 *  - `src/main/scala-3`
 *
 * Mix in [[CrossScalaVersionRanges]] for version range support.
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
