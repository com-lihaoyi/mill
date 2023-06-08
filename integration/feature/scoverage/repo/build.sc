// Reproduction of issue https://github.com/com-lihaoyi/mill/issues/2579
// and issue https://github.com/com-lihaoyi/mill/issues/2582

// mill plugins
import $ivy.`com.lihaoyi::mill-contrib-scoverage:`
import $ivy.`com.github.lolgab::mill-mima::0.0.23`

// imports
import mill._
import mill.contrib.scoverage.ScoverageModule
import mill.scalalib._

object Deps {
  val millVersion = "0.11.0"
  val millMain = ivy"com.lihaoyi::mill-main:${millVersion}"
  val scalaTest = ivy"org.scalatest::scalatest:3.2.16"
}

object core extends Cross[CoreCross]("2.13.11")
trait CoreCross extends CrossScalaModule with ScoverageModule {
  override def scoverageVersion = "2.0.10"
  object test extends ScoverageTests with TestModule.ScalaTest {
    override def ivyDeps = Agg(Deps.scalaTest, Deps.millMain)
  }
}

