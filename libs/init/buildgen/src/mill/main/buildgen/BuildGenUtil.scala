package mill.main.buildgen

import mill.init.Util
import mill.main.buildgen.BuildInfo.millVersion

/**
 * Shared utilities for build file generation (both Scala and YAML formats).
 */
object BuildGenUtil {

  inline def lineSep: String = System.lineSeparator()

  def fillPackages(packages: Seq[PackageSpec]): Seq[PackageSpec] = {
    def recurse(dir: os.SubPath): Seq[PackageSpec] = {
      val root = packages.find(_.dir == dir).getOrElse(PackageSpec.root(dir))
      val nested = packages.collect {
        case pkg if pkg.dir.startsWith(dir) && pkg.dir != dir =>
          os.sub / pkg.dir.segments.take(dir.segments.length + 1)
      }.distinct.flatMap(recurse)
      root +: nested
    }
    recurse(os.sub)
  }

  def mergePackages(root: PackageSpec, nested: Seq[PackageSpec]): PackageSpec = {
    def newChildren(parentDir: os.SubPath): Seq[ModuleSpec] = {
      val childDepth = parentDir.segments.length + 1
      nested.collect {
        case child if child.dir.startsWith(parentDir) && child.dir.segments.length == childDepth =>
          child.module.copy(children = child.module.children ++ newChildren(child.dir))
      }
    }
    root.copy(module =
      root.module.copy(children = root.module.children ++ newChildren(root.dir))
    )
  }

  def renderImports(module: ModuleSpec): String = {
    val imports = module.tree.flatMap(_.imports)
    ("mill.*" +: imports).distinct.sorted.map("import " + _).mkString(lineSep)
  }

  def renderExtendsClause(supertypes: Seq[String]): String = {
    if (supertypes.isEmpty) "extends Module"
    else supertypes.mkString("extends ", ", ", "")
  }

  def resolveMillVersion: String = {
    if (sys.env.contains("MILL_UNSTABLE_VERSION")) "SNAPSHOT" else millVersion
  }

  def resolveMillJvmVersion(millJvmVersion: Option[String]): String = {
    millJvmVersion.getOrElse {
      val path = os.pwd / ".mill-jvm-version"
      if (os.exists(path)) os.read(path) else "system"
    }
  }

  def removeExistingBuildFiles(): Unit = {
    val existingBuildFiles = Util.buildFiles(os.pwd)
    if (existingBuildFiles.nonEmpty) {
      println("removing existing build files ...")
      for (file <- existingBuildFiles) do os.remove(file)
    }
  }
}
