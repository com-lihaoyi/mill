package mill.scalalib

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
