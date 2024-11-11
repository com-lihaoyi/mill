package mill.main.build

import mill.main.client.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames, rootModuleAlias}
import mill.runner.FileImportGraph.backtickWrap

import scala.collection.immutable.{SortedMap, SortedSet}

/**
 * A Mill build module defined as a Scala object.
 *
 * @param imports Scala import statements
 * @param companions build companion objects defining constants
 * @param supertypes Scala supertypes inherited by the object
 * @param inner Scala object code
 * @param outer additional Scala type definitions like base module traits
 */
@mill.api.experimental
case class BuildObject(
    imports: SortedSet[String],
    companions: BuildObject.Companions,
    supertypes: Seq[String],
    inner: String,
    outer: String
)
@mill.api.experimental
object BuildObject {

  type Constants = SortedMap[String, String]
  type Companions = SortedMap[String, Constants]

  private val linebreak =
    """
      |""".stripMargin

  private val linebreak2 =
    """
      |
      |""".stripMargin

  private def extend(supertypes: Seq[String]): String = supertypes match {
    case Seq() => ""
    case Seq(head) => s"extends $head"
    case head +: tail => tail.mkString(s"extends $head with ", " with ", "")
  }

  implicit class NodeOps(private val self: Node[BuildObject]) extends AnyVal {

    def file: os.RelPath = {
      val name = if (self.dirs.isEmpty) rootBuildFileNames.head else nestedBuildFileNames.head
      os.rel / self.dirs / name
    }

    def source: os.Source = {
      val pkg = self.pkg
      val BuildObject(imports, companions, supertypes, inner, outer) = self.module
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

      s"""package $pkg
         |
         |$importStatements
         |
         |$companionTypedefs
         |
         |object `package` ${extend(supertypes)} {
         |
         |$inner
         |}
         |
         |$outer
         |""".stripMargin
    }
  }

  implicit class TreeOps(private val self: Tree[Node[BuildObject]]) extends AnyVal {

    def compact: Tree[Node[BuildObject]] = {
      def merge(parentCompanions: Companions, childCompanions: Companions): Companions = {
        var mergedParentCompanions = parentCompanions

        childCompanions.foreach { case entry @ (objectName, childConstants) =>
          val parentConstants = mergedParentCompanions.getOrElse(objectName, null)
          if (null == parentConstants) mergedParentCompanions += entry
          else {
            if (childConstants.exists { case (k, v) => v != parentConstants.getOrElse(k, v) })
              return null
            else mergedParentCompanions += ((objectName, parentConstants ++ childConstants))
          }
        }

        mergedParentCompanions
      }

      self.transform[Node[BuildObject]] { (node, subtrees) =>
        val isRoot = node.dirs.isEmpty
        var parent = node.module
        val unmerged = Seq.newBuilder[Tree[Node[BuildObject]]]

        subtrees.iterator.foreach {
          case subtree @ Tree(Node(_ :+ dir, child), Seq()) if child.outer.isEmpty =>
            val BuildObject(imports, companions, supertypes, inner, _) = child
            merge(parent.companions, companions) match {
              case null => unmerged += subtree
              case parentCompanions =>
                val parentImports =
                  parent.imports ++ (
                    if (isRoot) imports.filterNot(_.startsWith("$file")) else imports
                  )
                val parentInner = {
                  val childName = backtickWrap(dir)
                  val childSupertypes = supertypes.diff(Seq("RootModule"))

                  s"""${parent.inner}
                     |
                     |object $childName ${extend(childSupertypes)}  {
                     |
                     |$inner
                     |}""".stripMargin
                }

                parent = parent.copy(
                  imports = parentImports,
                  companions = parentCompanions,
                  inner = parentInner
                )
            }
          case subtree => unmerged += subtree
        }

        val unmergedSubtrees = unmerged.result()
        if (isRoot && unmergedSubtrees.isEmpty) {
          parent = parent.copy(imports = parent.imports.filterNot(_.startsWith("$packages")))
        }

        Tree(node.copy(module = parent), unmergedSubtrees)
      }
    }
  }
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

  implicit class Ops[Module](private val self: Node[Module]) extends AnyVal {

    def pkg: String =
      (rootModuleAlias +: self.dirs).iterator.map(backtickWrap).mkString(".")
  }
}
