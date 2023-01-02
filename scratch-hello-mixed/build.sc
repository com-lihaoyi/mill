import mill._
import mill.scalalib._

object scala extends ScalaModule {

  def scalaVersion = "2.13.10"

  object test extends Tests with TestModule.Munit {
    def ivyDeps = Agg(
      ivy"org.scalameta::munit::0.7.29"
    )
  }
}

object java extends JavaModule {
}
