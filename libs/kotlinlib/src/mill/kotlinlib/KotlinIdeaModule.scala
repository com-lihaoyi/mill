package mill.kotlinlib

import mill.api.daemon.internal.idea.{Element, JavaFacet}
import mill.api.{Task, TaskCtx, experimental}
import mill.scalalib.internal.IdeUtils

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

      if (kotlinFriendModules.nonEmpty) {
        val redirectedFriendPaths = Task.traverse(kotlinFriendModules)({
          case friend: KotlinModule => Task.Anon {
              Some(friend.genIdeaInternalExt().ideaCompileOutput().path)
            }
          case friend => Task.Anon {
              Task.ctx().log.warn(
                s"Friend module ${friend.moduleSegments.render} is not a KotlinIdeaModule, cannot redirect friend paths for it"
              )
              None
            }
        })().flatten

        if (redirectedFriendPaths.nonEmpty) {
          options += redirectedFriendPaths.map(path =>
            "$MODULE_DIR$/../../" + (path.relativeTo(Task.ctx().workspace)).toString
          ).mkString("-Xfriend-paths=", ",", "")
        }
      }

      options.mkString(" ")
    }

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
              childs = Seq(
                if (kotlinFriendModules.nonEmpty) {
                  Element(
                    "additionalVisibleModuleNames",
                    childs = kotlinFriendModules.map(friend =>
                      Element(
                        "friend",
                        childsOrText = Seq(IdeUtils.moduleName(friend.moduleSegments))
                      )
                    )
                  )
                } else null,
                Element(
                  "compilerSettings",
                  childs = Seq(
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
