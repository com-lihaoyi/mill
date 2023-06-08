// Reproduction of issue https://github.com/com-lihaoyi/mill/issues/2579

// mill plugins
import $ivy.`com.lihaoyi::mill-contrib-scoverage:`

// imports
import mill._
import mill.contrib.scoverage.ScoverageModule
import mill.scalalib._

// Reproduction of issue https://github.com/com-lihaoyi/mill/issues/2582
val baseDir = build.millSourcePath

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

object extra extends ScalaModule with ScoverageModule {
  override def scoverageVersion = "2.0.10"
  override def scalaVersion = "2.13.11"
  // customized scoverage data
  override lazy val scoverage: ScoverageData = new ScoverageData {
    // some customizations
  }
}