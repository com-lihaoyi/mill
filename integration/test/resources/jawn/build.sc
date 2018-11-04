import mill.scalalib
import mill.Cross
import mill.scalalib.{Dep, TestModule, DepSyntax, Lib}
object jawn extends Cross[JawnModule]("2.10.6", "2.11.11", "2.12.3")
class JawnModule(crossVersion: String) extends mill.Module{
  override def millSourcePath = super.millSourcePath / os.up / os.up

  trait JawnModule extends scalalib.SbtModule{
    def scalaVersion = crossVersion
    def scalacOptions = Seq(
      "-deprecation",
      "-optimize",
      "-unchecked"
    )
    def testModuleDeps: Seq[TestModule] = Nil
    object test extends Tests{
      def moduleDeps = super.moduleDeps ++ testModuleDeps
      def ivyDeps = Agg(
        ivy"org.scalatest::scalatest:3.0.3",
        ivy"org.scalacheck::scalacheck:1.13.5"
      )
      def testFrameworks = Seq("org.scalatest.tools.Framework")
    }
  }
  object parser extends JawnModule

  object util extends JawnModule{
    def moduleDeps = Seq(parser)
    def testModuleDeps = Seq(parser.test)
  }
  object ast extends JawnModule{
    def moduleDeps = Seq(parser, util)
    def testModuleDeps = Seq(parser.test, util.test)
  }
  class Support(ivyDeps0: Dep*)(implicit ctx: mill.define.Ctx) extends JawnModule{
    def moduleDeps = Seq(parser)
    def ivyDeps = Agg.from(ivyDeps0)
  }
  object support extends mill.Module{
    object argonaut extends Support(ivy"io.argonaut::argonaut:6.2")
    object json4s extends Support(ivy"org.json4s::json4s-ast:3.5.2")

    object play extends Support(){
      def ivyDeps = mill.T{
        Lib.scalaBinaryVersion(scalaVersion()) match{
          case "2.10" => Agg(ivy"com.typesafe.play::play-json:2.4.11")
          case "2.11" => Agg(ivy"com.typesafe.play::play-json:2.5.15")
          case _ => Agg(ivy"com.typesafe.play::play-json:2.6.0")
        }
      }
    }

    object rojoma extends Support(ivy"com.rojoma::rojoma-json:2.4.3")
    object rojomaV3 extends Support(ivy"com.rojoma::rojoma-json-v3:3.7.2"){
      override def millSourcePath = super.millSourcePath / os.up / "rojoma-v3"
    }
    object spray extends Support(ivy"io.spray::spray-json:1.3.3")
  }
}
