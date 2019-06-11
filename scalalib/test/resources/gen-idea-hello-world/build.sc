import mill.scalalib
import $ivy.`com.lihaoyi::mill-contrib-buildinfo:0.4.0`

trait HelloWorldModule extends scalalib.ScalaModule {
  def scalaVersion = "2.12.4"
  object test extends super.Tests {
    def testFrameworks = Seq("utest.runner.Framework")
  }
}

object HelloWorld extends HelloWorldModule
