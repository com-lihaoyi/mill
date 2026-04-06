package mill.kotlinlib

import mill.api.{Task, experimental}
import mill.api.daemon.internal.idea.{Element, JavaFacet}

/**
 * Adds a JavaFacet to the generated .iml file with the Kotlin compiler options.
 * This allows the IntelliJ Kotlin plugin to pick up the correct compiler options
 * for the module.
 */
@experimental
trait KotlinIdeaModule extends KotlinModule {
  override def ideaJavaModuleFacets(ideaConfigVersion: Int): Task[Seq[JavaFacet]] = Task.Anon {
    def stringArgElement(name: String, arg: String): Element =
      Element(
        "stringArg",
        attributes = Map(
          "name" -> name,
          "arg" -> arg
        )
      )

    val facets = ideaConfigVersion match {
      case 4 => {
        Seq(
          JavaFacet(
            "kotlin-language",
            "Kotlin",
            Element(
              "configuration",
              attributes = Map(
                "version" -> "5"
              ),
              childs = Seq(
                Element(
                  "compilerSettings",
                  childs = Seq(
                    Element(
                      "option",
                      attributes = Map(
                        "name" -> "additionalArguments",
                        "value" -> allKotlincOptions().mkString(" ")
                      )
                    )
                  )
                ),
                Element(
                  "compilerArguments",
                  childs = Seq(
                    Element(
                      "stringArguments",
                      childs =
                        Seq(
                          stringArgElement("apiVersion", kotlinApiVersion()),
                          stringArgElement("languageVersion", kotlinLanguageVersion())
                        ) ++ when(jvmVersion().nonEmpty)(
                          stringArgElement("jvmTarget", jvmVersion())
                        )
                    )
                  )
                )
              )
            )
          )
        )
      }
      case _ => Seq.empty
    }
    super.ideaJavaModuleFacets(ideaConfigVersion)() ++ facets
  }
}
