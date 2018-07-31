import mill.Agg
import mill.scalalib._

trait JUnitTests extends TestModule{
  def testFrameworks = Seq("com.novocode.junit.JUnitFramework")
  def ivyDeps = Agg(ivy"com.novocode:junit-interface:0.11")
}

object core extends JavaModule{
  object test extends Tests with JUnitTests
}
object app extends JavaModule{
  def moduleDeps = Seq(core)
  object test extends Tests with JUnitTests
}

object scalapb extends mill.scalapblib.ScalaPBModule {
  def scalaVersion = "2.12.4"
  def scalaPBVersion = "0.7.4"
}
