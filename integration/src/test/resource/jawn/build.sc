import mill.scalalib
import mill.define.Cross
import mill.scalalib.{Dep, TestModule, Module}

val jawn = Cross("2.10.6", "2.11.11", "2.12.3").map(new Jawn(_))
class Jawn(crossVersion: String) extends mill.Module{
  trait JawnModule extends scalalib.SbtModule{ outer =>
    def scalaVersion = crossVersion
    def scalacOptions = Seq(
      "-deprecation",
      "-optimize",
      "-unchecked"
    )
    def testProjectDeps: Seq[TestModule] = Nil
    object test extends this.Tests{
      def projectDeps = super.projectDeps ++ testProjectDeps
      def ivyDeps = Seq(
        Dep("org.scalatest", "scalatest", "3.0.3"),
        Dep("org.scalacheck", "scalacheck", "1.13.5")
      )
      def testFramework = "org.scalatest.tools.Framework"
    }
  }
  object Parser extends JawnModule{
    def basePath = ammonite.ops.pwd / 'target / 'workspace / 'jawn / 'parser
  }
  object Util extends JawnModule{
    def projectDeps = Seq(Parser)
    def testProjectDeps = Seq(Parser.test)
    def basePath = ammonite.ops.pwd / 'target / 'workspace / 'jawn / 'util
  }
  object Ast extends JawnModule{
    def projectDeps = Seq(Parser, Util)
    def testProjectDeps = Seq(Parser.test, Util.test)
    def basePath = ammonite.ops.pwd / 'target / 'workspace / 'jawn / 'ast
  }
  class Support(name: String, ivyDeps0: Dep*) extends JawnModule{
    def projectDeps = Seq[Module](Parser)
    def basePath = ammonite.ops.pwd / 'target / 'workspace / 'jawn / 'support / name
    def ivyDeps = ivyDeps0
  }
  object Argonaut extends Support("argonaut", Dep("io.argonaut", "argonaut", "6.2"))
  object Json4s extends Support("json4s", Dep("org.json4s", "json4s-ast", "3.5.2"))

  object Play extends Support("play"){
    def ivyDeps = mill.T{
      scalaBinaryVersion() match{
        case "2.10" => Seq(Dep("com.typesafe.play", "play-json", "2.4.11"))
        case "2.11" => Seq(Dep("com.typesafe.play", "play-json", "2.5.15"))
        case _ => Seq(Dep("com.typesafe.play", "play-json", "2.6.0"))
      }
    }
  }

  object Rojoma extends Support("rojoma", Dep("com.rojoma", "rojoma-json", "2.4.3"))
  object RojomaV3 extends Support("rojoma-v3", Dep("com.rojoma", "rojoma-json-v3", "3.7.2"))
  object Spray extends Support("spray", Dep("io.spray", "spray-json", "1.3.3"))
}