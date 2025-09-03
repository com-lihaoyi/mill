package mill.scalalib

/**
 * A [[mill.api.Cross]] that extends the [[SbtPlatformModule]] layout with additional source
 * directories with the scala version patterns as suffix, e.g.
 *  - src/main/scala-2
 *  - src/main/scala-2.12
 *  - src/main/scala-2.13
 *  - js/src/main/scala-2
 *  - js/src/main/scala-2.13
 *  - jvm/src/main/scala-2.12
 *  - jvm/src/main/scala-2.13
 *  - native/src/main/scala-2
 *  - native/src/main/scala-3
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
