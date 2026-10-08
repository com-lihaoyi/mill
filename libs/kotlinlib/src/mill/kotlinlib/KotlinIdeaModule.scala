package mill.kotlinlib

import mill.api.daemon.internal.idea.{Element, JavaFacet}
import mill.api.{BuildCtx, Task, TaskCtx, experimental}
import mill.api.daemon.internal.IdeUtils

private lazy val FriendPathsPattern = "^-Xfriend-paths=(.+)$".r

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

    lazy val redirectedCompilerOptions = {
      val options = scala.collection.mutable.Buffer.empty[String]

      options ++= allKotlincOptions().filterNot {
        case FriendPathsPattern(_) => true
        case _ => false
      }

      if (kotlinFriendModulesChecked.nonEmpty) {
        val redirectedFriendPaths = Task.traverse(kotlinFriendModulesChecked)(friend =>
          Task.Anon { friend.genIdeaInternalExt().ideaCompileOutput().path }
        )()
        options += redirectedFriendPaths
          .map(path => "$MODULE_DIR$/../../" + (path.relativeTo(BuildCtx.workspaceRoot)).toString)
          .mkString("-Xfriend-paths=", ",", "")
      }

      options.mkString(" ")
    }

    lazy val friendElements = kotlinFriendModulesChecked
      .flatMap(friend => IdeUtils.moduleName(friend.moduleSegments))
      .map(friendName => Element("friend", childsOrText = Seq(friendName)))

    val facets = ideaConfigVersion match {
      case 4 => {
        Seq(
          JavaFacet(
            "kotlin-language",
            "Kotlin",
            Element(
              "configuration",
              attributes = Map(
                "version" -> "5",
                "useProjectSettings" -> "false"
              ),
              childsOrText = Seq(
                if (friendElements.nonEmpty) {
                  Element(
                    "additionalVisibleModuleNames",
                    childsOrText = friendElements
                  )
                } else null,
                Element(
                  "compilerSettings",
                  childsOrText = Seq(
                    Element(
                      "option",
                      attributes = Map(
                        "name" -> "additionalArguments",
                        "value" -> redirectedCompilerOptions
                      )
                    )
                  )
                ),
                Element(
                  "compilerArguments",
                  childsOrText = Seq(
                    Element(
                      "stringArguments",
                      childsOrText =
                        Seq(
                          stringArgElement("apiVersion", kotlinApiVersion()),
                          stringArgElement("languageVersion", kotlinLanguageVersion())
                        ) ++ when(jvmVersion().nonEmpty)(
                          stringArgElement("jvmTarget", jvmVersion())
                        )
                    )
                  )
                )
              ).filterNot(_ == null)
            )
          )
        )
      }
      case _ => Seq.empty
    }
    super.ideaJavaModuleFacets(ideaConfigVersion)() ++ facets
  }
}
