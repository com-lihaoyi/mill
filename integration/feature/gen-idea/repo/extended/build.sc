import $ivy.`org.scalameta::munit:0.7.29`

import mill._
import mill.api.PathRef
import mill.scalalib
import mill.scalalib.GenIdeaModule._
import mill.scalalib.TestModule

trait HelloWorldModule extends scalalib.ScalaModule {
  override def scalaVersion = "2.13.6"
  object test extends ScalaModuleTests with TestModule.Utest

  override def generatedSources = T {
    Seq(PathRef(T.dest / "classes"))
  }

  object subScala3 extends scalalib.ScalaModule {
    override def scalaVersion = "3.0.2"
  }

  def ideaJavaModuleFacets(ideaConfigVersion: Int): Task[Seq[JavaFacet]] =
    T.task {
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
  ): Task[Seq[IdeaConfigFile]] = T.task {
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
            os.sub / "compiler.xml",
            component = "AjcSettings",
            config = Seq(
              Element(
                "option",
                Map("name" -> "ajcPath", "value" -> "/tmp/aspectjtools.jar")
              )
            )
          ),
          IdeaConfigFile(
            os.sub / "compiler.xml",
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
