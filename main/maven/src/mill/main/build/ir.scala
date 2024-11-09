package mill.main.build

import mill.main.client.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames, rootModuleAlias}
import mill.runner.FileImportGraph.backtickWrap

import scala.collection.immutable.{SortedMap, SortedSet}

/**
 * A Mill build module defined as a Scala object.
 *
 * @param imports Scala import statements
 * @param companions build companion objects defining constants
 * @param outer additional Scala type definitions like build module base traits
 * @param supertypes Scala supertypes inherited by the object
 * @param inner Scala object code
 */
@mill.api.experimental
case class BuildObject(
    imports: SortedSet[String],
    companions: BuildObject.Companions,
    outer: String,
    supertypes: Seq[String],
    inner: String
)
@mill.api.experimental
object BuildObject {

  type Constants = SortedMap[String, String]
  type Companions = SortedMap[String, Constants]
}

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
    """
      |""".stripMargin

  private val linebreak2 =
    """
      |
      |""".stripMargin

  implicit class BuildOps(private val self: Node[BuildObject]) extends AnyVal {

    def file: os.RelPath = {
      val name = if (self.dirs.isEmpty) rootBuildFileNames.head else nestedBuildFileNames.head
      os.rel / self.dirs / name
    }

    def source: os.Source = {
      val pkg = self.pkg
      val BuildObject(imports, companions, outer, supertypes, inner) = self.module
      val importStatements = imports.iterator.map("import " + _).mkString(linebreak)
      val companionTypedefs = companions.iterator.map {
        case (_, vals) if vals.isEmpty => ""
        case (name, vals) =>
          val members =
            vals.iterator.map { case (k, v) => s"val $k = $v" }.mkString(linebreak)

          s"""object $name {
             |
             |$members
             |}""".stripMargin
      }.mkString(linebreak2)
      val extendsClause = supertypes match {
        case Seq() => ""
        case Seq(head) => s"extends $head"
        case head +: tail => tail.mkString(s"extends $head with ", " with ", "")
      }

      s"""package $pkg
         |
         |$importStatements
         |
         |$companionTypedefs
         |
         |$outer
         |
         |object `package` $extendsClause {
         |
         |$inner
         |}""".stripMargin
    }
  }

  implicit class PackageOps[Module](private val self: Node[Module]) extends AnyVal {

    def pkg: String =
      (rootModuleAlias +: self.dirs).iterator.map(backtickWrap).mkString(".")
  }
}
