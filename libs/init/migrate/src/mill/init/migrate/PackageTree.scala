package mill.init.migrate

import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames}

import java.io.PrintStream
import scala.collection.mutable
import scala.util.Using

opaque type PackageTree = Tree[PackageRepr]
object PackageTree {

  def fill(packages: Seq[PackageRepr]): PackageTree = {
    Tree.gen(Seq.empty[String]): segments =>
      val pkg = packages.find(_.segments == segments).getOrElse(PackageRepr.empty(segments))
      val nextDepth = segments.length + 1
      val (children, descendants) = packages.iterator.map(_.segments)
        .filter(_.length > segments.length)
        .partition(_.length == nextDepth)
      val children0 =
        if (children.nonEmpty) children else descendants.map(_.take(nextDepth)).distinct
      (pkg, children0.toSeq.sortBy(os.sub / _))
  }

  def writeFiles(packages: PackageTree, writer: PackageWriter) = {
    val root +: nested = packages.iterator.toSeq: @unchecked
    Using.resource(PrintStream(os.write.outputStream(os.pwd / rootBuildFileNames.get(0)))):
      writer.writePackage(_, root)
    for pkg <- nested do
      Using.resource(
        PrintStream(os.write.outputStream(os.pwd / pkg.segments / nestedBuildFileNames.get(0)))
      ):
        writer.writePackage(_, pkg)
  }

  extension (self: PackageTree)

    def namesByDep: Map[String, String] = {
      val names = mutable.Set.empty[String]
      val namesByDep = mutable.Map.empty[String, String]
      def sanitizeName(name: String) = name.split("\\W") match
        case Array(head) => head
        case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
      def addName(dep: String): Unit = {
        if (namesByDep.contains(dep)) return
        val artifact = dep.dropWhile(_ != ':').dropWhile(_ == ':').takeWhile(_ != ':')
        var name = sanitizeName(artifact)
        if (names.contains(name)) name += "_" + names.count(_.startsWith(name)).toString
        names.add(name)
        namesByDep.addOne(dep -> name)
      }
      for
        pkg <- self.iterator
        module <- pkg.modules.iterator
        config <- module.main.configs.iterator ++ module.test.iterator.flatMap(_.configs)
      do
        config match
          case config: JavaModuleConfig =>
            for dep <- config.mvnDeps do addName(dep)
            for dep <- config.compileMvnDeps do addName(dep)
            for dep <- config.runMvnDeps do addName(dep)
          case config: ScalaModuleConfig =>
            for dep <- config.scalacPluginMvnDeps do addName(dep)
          case _ =>
      namesByDep.toMap
    }

    def unified: PackageTree = {
      val rootPackage = self.root
      val rootModuleTree = rootPackage.modules
      val rootModuleChildTrees = rootModuleTree.children
      val nestedPackages = self.children
      if (
        nestedPackages.iterator.map(_.root.segments.last).exists(name =>
          rootModuleChildTrees.exists(_.root.main.name == name)
        )
      ) self
      else
        val nestedModuleChildTrees =
          for nestedPackage <- nestedPackages yield nestedPackage.transform[ModuleRepr]:
            (pkg, nestedModuleChildTrees) =>
              val Tree(rootModule, rootModuleChildTrees) = pkg.modules
              Tree(
                rootModule.copy(main = rootModule.main.copy(pkg.segments.last)),
                rootModuleChildTrees ++ nestedModuleChildTrees
              )
        Tree(rootPackage.copy(modules =
          rootModuleTree.copy(children = rootModuleChildTrees ++ nestedModuleChildTrees)
        ))
    }
}
