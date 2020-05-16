// build.sc
import mill._, scalalib._

object foo extends ScalaModule{
  def scalaVersion = "2.13.1"
  object test extends Tests{
    def ivyDeps = Agg(ivy"com.lihaoyi::utest::0.7.3")
    def testFrameworks = Seq("utest.runner.Framework")
  }
}
