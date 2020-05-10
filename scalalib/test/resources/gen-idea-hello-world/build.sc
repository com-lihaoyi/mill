import mill.scalalib

trait HelloWorldModule extends scalalib.ScalaModule {
  def scalaVersion = "2.12.4"
  object test extends super.Tests {
    def testFrameworks = Seq("utest.runner.Framework")
  }
}

object HelloWorld extends HelloWorldModule

object HiddenWorld extends HelloWorldModule {
  override def skipIdea = true
}