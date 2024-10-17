import mill._, scalalib._

object sandbox extends SbtModule {
  def scalaVersion = "3.2.1"
  object test extends Tests {
    def ivyDeps =
      Agg(ivy"dev.zio::zio-test::2.0.5", ivy"dev.zio::zio-test-sbt::2.0.5")
    def testFramework = "zio.test.sbt.ZTestFramework"
  }
}
