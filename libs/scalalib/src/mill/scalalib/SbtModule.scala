package mill.scalalib

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sourcesFolders = super.sourcesFolders ++ Seq("src/main/scala")

  trait SbtTests extends ScalaTests with MavenTests {
    override def sourcesFolders =
      super.sourcesFolders ++ Seq(os.sub / "src" / testModuleName / "scala")
  }
}
