import mill.api.PathRef
import mill.scalalib
import mill.define.Command
import mill.scalalib.GenIdeaModule._

trait HelloWorldModule extends scalalib.ScalaModule {
  def scalaVersion = "2.12.4"
  object test extends super.Tests {
    def testFrameworks = Seq("utest.runner.Framework")
  }

  def generatedSources = T {
    Seq(PathRef(T.ctx().dest / "classes"))
  }

  def ideaJavaModuleFacets(ideaConfigVersion: Int): Command[Seq[JavaFacet]] = T.command {
    ideaConfigVersion match {
      case 4 =>
        Seq(
          JavaFacet("AspectJ", "AspectJ",
            Element("configuration", childs = Seq(
              Element("projectLibrary", childs = Seq(
                Element("option", Map("name" -> "name", "value" -> "/tmp"))
              ))
            ))
          )
        )
    }
  }

  override def ideaConfigFiles(ideaConfigVersion: Int): Command[Seq[IdeaConfigFile]] = T.command {
    ideaConfigVersion match {
      case 4 =>
        Seq(
          IdeaConfigFile(
            name = "compiler.xml",
            component = "AjcSettings",
            config = Seq(Element("option", Map("name" -> "ajcPath", "value" -> "/tmp/aspectjtools.jar")))),
          IdeaConfigFile(
            name = "compiler.xml",
            component = "CompilerConfiguration",
            config = Seq(Element("option", Map("name" -> "DEFAULT_COMPILER", "value" -> "ajc")))
          )
        )
    }
  }
}

object HelloWorld extends HelloWorldModule
