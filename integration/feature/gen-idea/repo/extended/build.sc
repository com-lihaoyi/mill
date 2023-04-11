import $ivy.`org.scalameta::munit:0.7.29`

import mill.api.PathRef
import mill.scalalib
import mill.define.Command
import mill.scalalib.GenIdeaModule._
import mill.scalalib.TestModule

trait HelloWorldModule extends scalalib.ScalaModule {
  override def scalaVersion = "2.13.10"
  object test extends super.Tests with TestModule.Utest

  override def generatedSources = T {
    Seq(PathRef(T.dest / "classes"))
  }

  object subScala3 extends scalalib.ScalaModule {
    override def scalaVersion = "3.0.2"
  }

  def ideaJavaModuleFacets(ideaConfigVersion: Int): Command[Seq[JavaFacet]] =
    T.command {
      ideaConfigVersion match {
        case 4 =>
          Seq(
            JavaFacet(
              "AspectJ",
              "AspectJ",
              Element(
                "configuration",
                childs = Seq(
                  Element(
                    "projectLibrary",
                    childs = Seq(
                      Element(
                        "option",
                        Map("name" -> "name", "value" -> "/tmp")
                      )
                    )
                  )
                )
              )
            )
          )
      }
    }

  override def ideaConfigFiles(
      ideaConfigVersion: Int
  ): Command[Seq[IdeaConfigFile]] = T.command {
    ideaConfigVersion match {
      case 4 =>
        Seq(
          // whole file
          IdeaConfigFile(
            os.sub / "runConfigurations" / "testrun.xml",
            None,
            Seq(Element("test"))
          ),
          // components in project file
          IdeaConfigFile(
            name = "compiler.xml",
            component = "AjcSettings",
            config = Seq(
              Element(
                "option",
                Map("name" -> "ajcPath", "value" -> "/tmp/aspectjtools.jar")
              )
            )
          ),
          IdeaConfigFile(
            name = "compiler.xml",
            component = "CompilerConfiguration",
            config = Seq(
              Element(
                "option",
                Map("name" -> "DEFAULT_COMPILER", "value" -> "ajc")
              )
            )
          )
        )
    }
  }
}

object HelloWorld extends HelloWorldModule
