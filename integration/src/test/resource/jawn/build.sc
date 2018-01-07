import mill.scalalib
import mill.CrossModule
import mill.scalalib.{Dep, TestModule, Module}

object jawn extends CrossModule(JawnModule, "2.10.6", "2.11.11", "2.12.3")
case class JawnModule(crossVersion: String) extends mill.Module{
  override def basePath = super.basePath / ammonite.ops.up

  trait JawnModule extends scalalib.SbtModule{
    def scalaVersion = crossVersion
    def scalacOptions = Seq(
      "-deprecation",
      "-optimize",
      "-unchecked"
    )
    def testProjectDeps: Seq[TestModule] = Nil
    object test extends Tests{
      def projectDeps = super.projectDeps ++ testProjectDeps
      def ivyDeps = Seq(
        Dep("org.scalatest", "scalatest", "3.0.3"),
        Dep("org.scalacheck", "scalacheck", "1.13.5")
      )
      def testFramework = "org.scalatest.tools.Framework"
    }
  }
  object parser extends JawnModule

  object util extends JawnModule{
    def projectDeps = Seq(parser)
    def testProjectDeps = Seq(parser.test)
  }
  object ast extends JawnModule{
    def projectDeps = Seq(parser, util)
    def testProjectDeps = Seq(parser.test, util.test)
  }
  class Support(ivyDeps0: Dep*)(implicit ctx: mill.Module.Ctx) extends JawnModule{
    def projectDeps = Seq[Module](parser)
    def ivyDeps = ivyDeps0
  }
  object support extends mill.Module{
    object argonaut extends Support(Dep("io.argonaut", "argonaut", "6.2"))
    object json4s extends Support(Dep("org.json4s", "json4s-ast", "3.5.2"))

    object play extends Support(){
      def ivyDeps = mill.T{
        scalaBinaryVersion() match{
          case "2.10" => Seq(Dep("com.typesafe.play", "play-json", "2.4.11"))
          case "2.11" => Seq(Dep("com.typesafe.play", "play-json", "2.5.15"))
          case _ => Seq(Dep("com.typesafe.play", "play-json", "2.6.0"))
        }
      }
    }

    object rojoma extends Support(Dep("com.rojoma", "rojoma-json", "2.4.3"))
    object rojomaV3 extends Support(Dep("com.rojoma", "rojoma-json-v3", "3.7.2")){
      override def basePath = super.basePath / ammonite.ops.up / "rojoma-v3"
    }
    object spray extends Support(Dep("io.spray", "spray-json", "1.3.3"))
  }
}