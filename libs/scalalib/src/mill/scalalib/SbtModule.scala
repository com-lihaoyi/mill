package mill.scalalib

import mill.api.Task

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sourcesFolders = sourcesFolders0 ++ Seq("src/main/scala")
  // Bincompat stub
  override def sources = Task.Sources(sourcesFolders*)

  trait SbtTests extends ScalaTests with MavenTests with SbtModule {
    override def sourcesFolders = sourcesFolders0 ++ Seq(os.sub / "src" / testModuleName / "scala")
    // Bincompat stub
    override def sources = Task.Sources(sourcesFolders*)
  }
}
