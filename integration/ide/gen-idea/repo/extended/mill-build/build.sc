import mill._
import mill.scalalib._

object build extends MillBuildRootModule {
  def ivyDeps = Agg(ivy"org.scalameta::munit:0.7.29")
}