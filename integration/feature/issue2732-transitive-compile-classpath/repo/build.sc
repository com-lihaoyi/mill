import mill._
import mill.scalalib._

object bar extends ScalaModule {
  def scalaVersion = "2.13.12"
  def moduleDeps = Seq(foo)
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"org.typelevel::cats-core::2.9.0",
  )
}
object foo extends ScalaModule {
  def scalaVersion = "2.13.12"
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"io.circe::circe-core::0.14.0"
  )
}