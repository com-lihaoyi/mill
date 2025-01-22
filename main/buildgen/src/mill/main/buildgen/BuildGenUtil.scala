package mill.main.buildgen

import mill.main.buildgen.BuildObject.Companions
import mill.main.client.CodeGenConstants.{
  buildFileExtensions,
  nestedBuildFileNames,
  rootBuildFileNames,
  rootModuleAlias
}
import mill.main.client.OutFiles
import mill.runner.FileImportGraph.backtickWrap

@mill.api.internal
object BuildGenUtil {
  def buildFile(dirs: Seq[String]): os.SubPath = {
    val name = if (dirs.isEmpty) rootBuildFileNames.head else nestedBuildFileNames.head
    os.sub / dirs / name
  }

  def buildFiles(workspace: os.Path): geny.Generator[os.Path] =
    os.walk.stream(workspace, skip = (workspace / OutFiles.out).equals)
      .filter(file => buildFileExtensions.contains(file.ext))

  def buildPackage(dirs: Seq[String]): String =
    (rootModuleAlias +: dirs).iterator.map(backtickWrap).mkString(".")

  def buildPackages[Module, Key](input: Tree[Node[Module]])(key: Module => Key)
      : Map[Key, String] =
    input.nodes.fold(Map.newBuilder[Key, String])((z, node) =>
      z += ((key(node.module), buildPackage(node.dirs)))
    ).result()

  def buildSource(node: Node[BuildObject]): os.Source = {
    val pkg = buildPackage(node.dirs)
    val BuildObject(imports, companions, supertypes, inner, outer) = node.module
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
       |object `package` ${mkExtends(supertypes)} {
       |
       |$inner
       |}
       |
       |$outer
       |""".stripMargin
  }

  def compact(tree: Tree[Node[BuildObject]]): Tree[Node[BuildObject]] = {
    println("compacting Mill build tree")

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

    tree.transform[Node[BuildObject]] { (node, children) =>
      var module = node.module
      val unmerged = Seq.newBuilder[Tree[Node[BuildObject]]]

      children.iterator.foreach {
        case child @ Tree(Node(_ :+ dir, nested), Seq()) if nested.outer.isEmpty =>
          val mergedCompanions = merge(module.companions, nested.companions)
          if (null == mergedCompanions) unmerged += child
          else {
            val mergedImports = module.imports ++ nested.imports
            val mergedInner = {
              val name = backtickWrap(dir)
              val supertypes = nested.supertypes.filterNot(_ == "RootModule")

              s"""${module.inner}
                 |
                 |object $name ${mkExtends(supertypes)}  {
                 |
                 |${nested.inner}
                 |}""".stripMargin
            }

            module = module.copy(
              imports = mergedImports,
              companions = mergedCompanions,
              inner = mergedInner
            )
          }
        case child => unmerged += child
      }

      val unmergedChildren = unmerged.result()
      if (node.dirs.isEmpty) {
        module = module.copy(imports = module.imports.filterNot(_.startsWith("$file")))
        if (unmergedChildren.isEmpty) {
          module = module.copy(imports = module.imports.filterNot(_ == "$packages._"))
        }
      }

      Tree(node.copy(module = module), unmergedChildren)
    }
  }

  def escape(value: String): String =
    pprint.Util.literalize(if (value == null) "" else value)

  def escapeOption(value: String): String =
    if (null == value) "None" else s"Some(\"$value\")"

  def interpIvy(
      group: String,
      artifact: String,
      version: String = null,
      tpe: String = null,
      classifier: String = null,
      excludes: IterableOnce[(String, String)] = Seq.empty
  ): String = {
    val sepVersion =
      if (null == version) {
        println(
          s"assuming $group:$artifact is a BOM dependency; if not, please specify version in the generated build file"
        )
        ""
      } else s":$version"
    val sepTpe = tpe match {
      case null | "" | "jar" => "" // skip default
      case tpe => s";type=$tpe"
    }
    val sepClassifier = classifier match {
      case null | "" => ""
      case s"$${$v}" => // drop values like ${os.detected.classifier}
        println(s"dropping classifier $${$v} for dependency $group:$artifact:$version")
        ""
      case classifier => s";classifier=$classifier"
    }
    val sepExcludes = excludes.iterator
      .map { case (group, artifact) => s";exclude=$group:$artifact" }
      .mkString

    s"ivy\"$group:$artifact$sepVersion$sepTpe$sepClassifier$sepExcludes\""
  }

  def isBom(gav: (String, String, String)): Boolean =
    gav._2.endsWith("-bom")

  def isNullOrEmpty(value: String): Boolean =
    null == value || value.isEmpty

  def linebreak: String =
    """
      |""".stripMargin

  def linebreak2: String =
    """
      |
      |""".stripMargin

  val mavenMainResourceDir: os.SubPath =
    os.sub / "src/main/resources"

  val mavenTestResourceDir: os.SubPath =
    os.sub / "src/test/resources"

  def mkArtifact(group: String, id: String, version: String): String =
    s"Artifact(${escape(group)}, ${escape(id)}, ${escape(version)})"

  def mkDeveloper(id: String, name: String, url: String, org: String, orgUrl: String): String =
    s"Developer(${escape(id)}, ${escape(name)}, ${escape(url)}, ${escapeOption(org)}, ${escapeOption(orgUrl)})"

  def mkExtends(supertypes: Seq[String]): String = supertypes match {
    case Seq() => ""
    case Seq(head) => s"extends $head"
    case head +: tail => tail.mkString(s"extends $head with ", " with ", "")
  }

  def mkLicense(
      id: String,
      name: String,
      url: String,
      isOsiApproved: Boolean = false,
      isFsfLibre: Boolean = false,
      distribution: String = "repo"
  ): String =
    s"License(${escape(id)}, ${escape(name)}, ${escape(url)}, $isOsiApproved, $isFsfLibre, ${escape(distribution)})"

  def mkPomSettings(
      description: String,
      organization: String,
      url: String,
      licenses: IterableOnce[String],
      versionControl: String,
      developers: IterableOnce[String]
  ): String = {
    val mkLicenses = licenses.iterator.mkString("Seq(", ", ", ")")
    val mkDevelopers = developers.iterator.mkString("Seq(", ", ", ")")
    s"PomSettings(${escape(description)}, ${escape(organization)}, ${escape(url)}, $mkLicenses, $versionControl, $mkDevelopers)"
  }

  def mkVersionControl(
      repo: String = null,
      connection: String = null,
      devConnection: String = null,
      tag: String = null
  ): String =
    s"VersionControl(${escapeOption(repo)}, ${escapeOption(connection)}, ${escapeOption(devConnection)}, ${escapeOption(tag)})"

  def mkZincWorker(moduleName: String, jvmId: String): String =
    s"""object $moduleName extends ZincWorkerModule {
       |  def jvmId = "$jvmId"
       |}""".stripMargin

  def optional(construct: String, args: IterableOnce[String]): String =
    optional(construct + "(", args, ",", ")")

  def optional(start: String, args: IterableOnce[String], sep: String, end: String): String = {
    val itr = args.iterator
    if (itr.isEmpty) ""
    else itr.mkString(start, sep, end)
  }

  def scalafmtConfigFile: os.Path =
    os.temp(
      """version = "3.8.4"
        |runner.dialect = scala213
        |newlines.source=fold
        |newlines.topLevelStatementBlankLines = [
        |  {
        |    blanks { before = 1 }
        |  }
        |]
        |""".stripMargin
    )

  def setArtifactName(name: String, dirs: Seq[String]): String =
    if (dirs.nonEmpty && dirs.last == name) "" // skip default
    else s"def artifactName = ${escape(name)}"

  def setBomIvyDeps(args: IterableOnce[String]): String =
    optional("def bomIvyDeps = super.bomIvyDeps() ++ Agg", args)

  def setIvyDeps(args: IterableOnce[String]): String =
    optional("def ivyDeps = super.ivyDeps() ++ Agg", args)

  def setModuleDeps(args: IterableOnce[String]): String =
    optional("def moduleDeps = super.moduleDeps ++ Seq", args)

  def setCompileIvyDeps(args: IterableOnce[String]): String =
    optional("def compileIvyDeps = super.compileIvyDeps() ++ Agg", args)

  def setCompileModuleDeps(args: IterableOnce[String]): String =
    optional("def compileModuleDeps = super.compileModuleDeps ++ Seq", args)

  def setRunIvyDeps(args: IterableOnce[String]): String =
    optional("def runIvyDeps = super.runIvyDeps() ++ Agg", args)

  def setRunModuleDeps(args: IterableOnce[String]): String =
    optional("def runModuleDeps = super.runModuleDeps ++ Seq", args)

  def setJavacOptions(args: IterableOnce[String]): String =
    optional(
      "def javacOptions = super.javacOptions() ++ Seq",
      args.iterator.map(escape)
    )

  def setRepositories(args: IterableOnce[String]): String =
    optional(
      "def repositoriesTask = Task.Anon { super.repositoriesTask() ++ Seq(",
      args,
      ", ",
      ") }"
    )

  def setResources(args: IterableOnce[os.SubPath]): String =
    optional(
      "def resources = Task.Sources { super.resources() ++ Seq(",
      args.iterator.map(sub => s"PathRef(millSourcePath / ${escape(sub.toString())})"),
      ", ",
      ") }"
    )

  def setPomPackaging(packaging: String): String =
    if (isNullOrEmpty(packaging) || "jar" == packaging) "" // skip default
    else {
      val pkg = if ("pom" == packaging) "PackagingType.Pom" else escape(packaging)
      s"def pomPackagingType = $pkg"
    }

  def setPomParentProject(artifact: String): String =
    if (isNullOrEmpty(artifact)) ""
    else s"def pomParentProject = Some($artifact)"

  def setPomSettings(arg: String): String =
    if (isNullOrEmpty(arg)) ""
    else s"def pomSettings = $arg"

  def setPublishVersion(arg: String): String =
    if (isNullOrEmpty(arg)) ""
    else s"def publishVersion = ${escape(arg)}"

  def setPublishProperties(args: IterableOnce[(String, String)]): String = {
    val tuples = args.iterator.map { case (k, v) => s"(${escape(k)}, ${escape(v)})" }
    optional("def publishProperties = super.publishProperties() ++ Map", tuples)
  }

  def setZincWorker(moduleName: String): String =
    s"def zincWorker = mill.define.ModuleRef($moduleName)"

  val testModulesByGroup: Map[String, String] = Map(
    "junit" -> "TestModule.Junit4",
    "org.junit.jupiter" -> "TestModule.Junit5",
    "org.testng" -> "TestModule.TestNg"
  )

  def write(tree: Tree[Node[BuildObject]]): Unit = {
    val nodes = tree.nodes.toSeq
    println(s"generated ${nodes.length} Mill build file(s)")

    println("removing existing Mill build files")
    val workspace = os.pwd
    buildFiles(workspace).foreach(os.remove.apply)

    nodes.foreach { node =>
      val file = buildFile(node.dirs)
      val src = buildSource(node)
      println(s"writing Mill build file to $file")
      os.write(workspace / file, src)
    }
  }
}
