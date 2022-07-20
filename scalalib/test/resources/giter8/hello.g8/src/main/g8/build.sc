import mill._, scalalib._

object $name;format="camel"$ extends ScalaModule {

  def scalaVersion = "2.13.8"
  
  object test extends Tests with TestModule.Munit {
    def ivyDeps = Agg(
      ivy"org.scalameta::munit::0.7.29"
    )
  }
}
