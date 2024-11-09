package mill.main.build

import mill.main.client.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames, rootModuleAlias}
import mill.runner.FileImportGraph.backtickWrap

import scala.collection.immutable.SortedMap

/**
 * A Mill build companion object containing constants.
 *
 * @param name Scala object name
 * @param vals named constants
 */
@mill.api.experimental
case class BuildCompanion(name: String, vals: SortedMap[String, String])

/**
 * A Mill build module defined as a Scala object.
 *
 * @param imports Scala import statements
 * @param typedefs additional Scala type definitions like build module base traits
 * @param companions build companion objects
 * @param name Scala object name
 * @param supertypes Scala supertypes inherited by the object
 * @param body Scala object code
 */
@mill.api.experimental
case class BuildDefinition(
    imports: Seq[String],
    typedefs: Seq[String],
    companions: Seq[BuildCompanion],
    name: String,
    supertypes: Seq[String],
    body: String
)

/**
 * A node representing a module in a build tree.
 *
 * @param dirs relative location in the build tree
 * @param module build module
 */
@mill.api.experimental
case class Node[Module](dirs: Seq[String], module: Module)
@mill.api.experimental
object Node {

  private val linebreak =
    s"""
       |""".stripMargin

  private val linebreak2 =
    s"""
       |
       |""".stripMargin

  implicit class BuildOps(private val self: Node[BuildDefinition]) extends AnyVal {

    def file: os.RelPath = {
      val name = if (self.dirs.isEmpty) rootBuildFileNames.head else nestedBuildFileNames.head
      os.rel / self.dirs / name
    }

    def source: os.Source = {
      val pkg = self.pkg
      val BuildDefinition(imports, typedefs, companions, name, supertypes, body) = self.module
      val companionTypedefs = companions.iterator.map {
        case BuildCompanion(_, vals) if vals.isEmpty => ""
        case BuildCompanion(name, vals) =>
          val members =
            vals.iterator.map { case (k, v) => s"val $k = $v" }.mkString(linebreak)

          s"""object $name {
             |
             |$members
             |}""".stripMargin
      }
      val extendsClause = supertypes match {
        case Seq() => ""
        case Seq(head) => s"extends $head"
        case head +: tail => tail.mkString(s"extends $head with ", " with ", "")
      }
      s"""package $pkg
         |
         |${imports.mkString(linebreak)}
         |
         |${typedefs.mkString(linebreak2)}
         |
         |${companionTypedefs.mkString(linebreak2)}
         |
         |object $name $extendsClause {
         |
         |$body
         |
         |}
         |""".stripMargin
    }
  }

  implicit class PackageOps[Module](private val self: Node[Module]) extends AnyVal {

    def pkg: String =
      (rootModuleAlias +: self.dirs).iterator.map(backtickWrap).mkString(".")
  }
}
